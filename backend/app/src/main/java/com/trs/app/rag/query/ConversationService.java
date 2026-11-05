package com.trs.app.rag.query;

import com.trs.app.rag.*;
import com.trs.app.rag.retrieval.RetrievalFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Short transactions for private conversation history; model calls happen elsewhere. @author czhcc */
@Service
public class ConversationService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final int timeout;
  private final JsonMapper json = JsonMapper.builder().build();

  /** Captures a request ID and only server-owned completed history. @author czhcc */
  public record Turn(UUID id, List<Map<String, String>> history) {}

  /** Uses the same bounded deadline as answer generation. @author czhcc */
  public ConversationService(JdbcTemplate jdbc, KnowledgeBaseService access,
      @Value("${rag.query.timeout-seconds:180}") int timeout) {
    this.jdbc = jdbc; this.access = access; this.timeout = timeout;
  }

  private Map<String, Object> owned(UUID kb, UUID id, String actor, boolean lock) {
    access.requireAccess(kb, actor, false);
    var rows = jdbc.queryForList("SELECT c.* FROM rag_conversation c JOIN app_user u ON u.id=c.user_id"
        + " WHERE c.knowledge_base_id=? AND c.id=? AND u.username=?" + (lock ? " FOR UPDATE OF c" : ""), kb, id, actor);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  /** Lists fifty recent conversations per page for this user and knowledge base. @author czhcc */
  public List<Map<String, Object>> list(UUID kb, String actor, int page) {
    access.requireAccess(kb, actor, false);
    if (page < 0 || page > 100000) throw new IllegalArgumentException();
    return jdbc.queryForList("SELECT c.id,c.title,c.created_at AS \"createdAt\",c.updated_at AS \"updatedAt\""
        + " FROM rag_conversation c JOIN app_user u ON u.id=c.user_id WHERE c.knowledge_base_id=? AND u.username=?"
        + " ORDER BY c.updated_at DESC,c.id LIMIT 50 OFFSET ?", kb, actor, page * 50);
  }

  /** Creates an empty private conversation, including for read-only knowledge-base members. @author czhcc */
  @Transactional
  public Map<String, Object> create(UUID kb, String actor) {
    access.requireAccess(kb, actor, false);
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO rag_conversation(knowledge_base_id,id,user_id) SELECT ?,?,id FROM app_user WHERE username=?", kb, id, actor);
    return Map.of("id", id, "title", "");
  }

  /** Deletes the caller's conversation and cascades its turns under the conversation lock. @author czhcc */
  @Transactional
  public void delete(UUID kb, UUID id, String actor) {
    owned(kb, id, actor, true);
    jdbc.update("DELETE FROM rag_conversation WHERE knowledge_base_id=? AND id=?", kb, id);
  }

  /** Reads a chronological page of up to fifty turns, including explicit interrupted states. @author czhcc */
  public List<Map<String, Object>> turns(UUID kb, UUID id, String actor, Long before) {
    owned(kb, id, actor, false);
    var rows = jdbc.query("SELECT id,turn_no,question,answer,status,result,expires_at<CURRENT_TIMESTAMP AS expired"
        + " FROM rag_conversation_turn WHERE knowledge_base_id=? AND conversation_id=? AND turn_no<? ORDER BY turn_no DESC LIMIT 50",
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", rs.getObject("id")); row.put("sequence", rs.getLong("turn_no"));
          row.put("question", rs.getString("question")); row.put("answer", rs.getString("answer"));
          row.put("status", rs.getString("status").equals("running") && rs.getBoolean("expired") ? "interrupted" : rs.getString("status"));
          row.put("result", rs.getString("result") == null ? null : json.readValue(rs.getString("result"), Map.class));
          return row;
        }, kb, id, before == null ? Long.MAX_VALUE : before);
    Collections.reverse(rows); return rows;
  }

  /** Supplies at most ten completed pairs; failed, cancelled and unfinished output is excluded. @author czhcc */
  public List<Map<String, String>> history(UUID kb, UUID id, String actor) {
    owned(kb, id, actor, false);
    var rows = jdbc.queryForList("SELECT question,answer FROM rag_conversation_turn WHERE knowledge_base_id=? AND conversation_id=?"
        + " AND status IN ('success','empty') ORDER BY turn_no DESC LIMIT 10", kb, id);
    Collections.reverse(rows);
    List<Map<String, String>> messages = new ArrayList<>();
    for (var row : rows) {
      messages.add(Map.of("role", "user", "content", row.get("question").toString()));
      messages.add(Map.of("role", "assistant", "content", row.get("answer").toString()));
    }
    return List.copyOf(messages);
  }

  /** Serializes questions in one conversation without holding locks during inference. @author czhcc */
  @Transactional
  public Turn begin(UUID kb, UUID id, String actor, UUID request, String question) {
    if (request == null || question == null || question.isBlank() || question.length() > 8000) throw new IllegalArgumentException();
    var conversation = owned(kb, id, actor, true);
    jdbc.update("UPDATE rag_conversation_turn SET status='failed' WHERE knowledge_base_id=? AND conversation_id=?"
        + " AND status='running' AND expires_at<=CURRENT_TIMESTAMP", kb, id);
    if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_conversation_turn WHERE knowledge_base_id=?"
        + " AND conversation_id=? AND (status='running' OR id=?))", Boolean.class, kb, id, request)))
      throw new RetrievalFailure("query_conversation_busy", 409);
    var history = history(kb, id, actor);
    long sequence = ((Number) conversation.get("next_turn")).longValue();
    jdbc.update("INSERT INTO rag_conversation_turn(knowledge_base_id,conversation_id,id,turn_no,question,expires_at) VALUES (?,?,?,?,?,?)",
        kb, id, request, sequence, question.strip(), Timestamp.from(Instant.now().plusSeconds(timeout)));
    jdbc.update("UPDATE rag_conversation SET next_turn=next_turn+1,title=CASE WHEN next_turn=1 THEN LEFT(?,80) ELSE title END,"
        + " updated_at=CURRENT_TIMESTAMP WHERE knowledge_base_id=? AND id=?", question.strip(), kb, id);
    return new Turn(request, history);
  }

  /** Publishes only a fully completed, still-owned and unexpired request. @author czhcc */
  @Transactional
  public void complete(UUID kb, UUID conversation, String actor, UUID id, QueryResult result) {
    owned(kb, conversation, actor, true);
    if (Thread.currentThread().isInterrupted() || !Set.of("success", "empty").contains(result.getStatus()))
      throw new RetrievalFailure("query_cancelled", 409);
    if (jdbc.update("UPDATE rag_conversation_turn SET status=?,answer=?,result=?::JSONB WHERE knowledge_base_id=? AND conversation_id=?"
        + " AND id=? AND status='running' AND expires_at>CURRENT_TIMESTAMP", result.getStatus(), result.getAnswer(),
        json.writeValueAsString(result), kb, conversation, id) != 1) throw new RetrievalFailure("query_cancelled", 409);
  }

  /** Records partial output as incomplete; stale failure writes cannot affect another turn. @author czhcc */
  @Transactional
  public void fail(UUID kb, UUID conversation, String actor, UUID id, String partial, boolean cancelled) {
    owned(kb, conversation, actor, true);
    jdbc.update("UPDATE rag_conversation_turn SET status=?,answer=? WHERE knowledge_base_id=? AND conversation_id=? AND id=? AND status='running'",
        cancelled ? "cancelled" : "failed", partial, kb, conversation, id);
  }
}
