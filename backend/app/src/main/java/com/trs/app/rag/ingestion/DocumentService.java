package com.trs.app.rag.ingestion;

import com.trs.app.rag.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable document/revision acceptance and membership-scoped retrieval.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentService {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  public static final String STRATEGY = "lightrag-fixed-utf8-safe-v1", PARSER = "txt-flexmark-v1";
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final RagTokenizer tokenizer;
  private final IngestionSettings settings;
  private final RevisionRepository revisions;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Stores a validated text revision and durable job without synchronous parsing.
   *
   * @author agent
   */
  public Submission acceptText(
      UUID kb, String username, String source, String text, String format, int size, int overlap) {
    access.requireAccess(kb, username, true);
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty document");
    byte[] bytes;
    try {
      var buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("Invalid Unicode");
    }
    return accept(kb, username, source, bytes, format, size, overlap);
  }

  /**
   * Accepts bounded TXT/Markdown bytes; decoding is deferred to the worker.
   *
   * @author agent
   */
  public Submission acceptUpload(
      UUID kb, String username, String filename, byte[] bytes, int size, int overlap) {
    access.requireAccess(kb, username, true);
    if (filename == null) throw new IllegalArgumentException("Missing filename");
    String source = filename.replace('\\', '/');
    if (source.startsWith("/") || source.contains(":")
        || Arrays.stream(source.split("/", -1)).anyMatch(part -> part.isBlank() || part.equals("..") || part.equals(".")))
      throw new IllegalArgumentException("Invalid relative filename");
    String lower = source.toLowerCase(Locale.ROOT);
    String format =
        lower.endsWith(".txt")
            ? "text"
            : lower.endsWith(".md") || lower.endsWith(".markdown") ? "markdown" : null;
    if (format == null
        || bytes == null
        || (bytes.length >= 5
            && new String(bytes, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")))
      throw new IllegalArgumentException("Unsupported file");
    return accept(kb, username, source, bytes, format, size, overlap);
  }

  private Submission accept(
      UUID kb, String username, String source, byte[] bytes, String format, int size, int overlap) {
    if (source == null
        || source.isBlank()
        || source.length() > 255
        || source.codePoints().anyMatch(Character::isISOControl)
        || !Set.of("text", "markdown").contains(format)
        || size < 16
        || size > 4096
        || overlap < 0
        || overlap > size / 2) throw new IllegalArgumentException("Invalid document settings");
    if (bytes.length == 0) throw new IllegalArgumentException("Empty document");
    if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    String hash = hash(bytes),
        key =
            hash(
                (hash
                        + "|"
                        + format
                        + "|"
                        + tokenizer.getName()
                        + "|"
                        + STRATEGY
                        + "|"
                        + PARSER
                        + "|"
                        + size
                        + "|"
                        + overlap)
                    .getBytes(StandardCharsets.UTF_8));
    Long user =
        jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    UUID doc =
        jdbc.queryForObject(
            "INSERT INTO rag_document (knowledge_base_id,id,source_name) VALUES (?,?,?) ON CONFLICT"
                + " (knowledge_base_id,source_name) DO UPDATE SET source_name=EXCLUDED.source_name,deleted=FALSE"
                + " RETURNING id",
            UUID.class,
            kb,
            UUID.randomUUID(),
            source.strip());
    var existing =
        jdbc.queryForList(
            "SELECT id FROM rag_document_revision WHERE knowledge_base_id=? AND document_id=? AND"
                + " revision_key=?",
            UUID.class,
            kb,
            doc,
            key);
    boolean duplicate = !existing.isEmpty();
    UUID revision = duplicate ? existing.getFirst() : UUID.randomUUID();
    if (!duplicate) {
      jdbc.update(
          "INSERT INTO rag_document_revision"
              + " (knowledge_base_id,id,document_id,revision_key,content_hash,format,size_bytes,tokenizer,chunk_strategy,parser_version,chunk_size,overlap,created_by)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
          kb,
          revision,
          doc,
          key,
          hash,
          format,
          bytes.length,
          tokenizer.getName(),
          STRATEGY,
          PARSER,
          size,
          overlap,
          user);
      jdbc.update(
          "INSERT INTO rag_document_blob (knowledge_base_id,revision_id,original) VALUES (?,?,?)",
          kb,
          revision,
          bytes);
      UUID job = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO rag_ingestion_job"
              + " (knowledge_base_id,id,revision_id,requested_by,max_attempts) VALUES (?,?,?,?,?)",
          kb,
          job,
          revision,
          user,
          settings.getMaxAttempts());
      jdbc.update(
          "INSERT INTO rag_job_event (knowledge_base_id,job_id,event) VALUES (?,?,'accepted')",
          kb,
          job);
    }
    jdbc.update(
        "UPDATE rag_document SET current_revision_id=?,pipeline_requested=TRUE,pipeline_paused=FALSE,pipeline_error=NULL WHERE knowledge_base_id=? AND id=?",
        revision,
        kb,
        doc);
    UUID job =
        jdbc.queryForObject(
            "SELECT id FROM rag_ingestion_job WHERE knowledge_base_id=? AND revision_id=?",
            UUID.class,
            kb,
            revision);
    if (duplicate) {
      jdbc.update("UPDATE rag_ingestion_job SET status='pending',attempts=0,cancel_requested=FALSE,"
          + " error_code=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,requested_by=?"
          + " WHERE knowledge_base_id=? AND id=? AND status IN ('failed','cancelled')", user, kb, job);
    }
    DocumentWorkflowService.enqueue(jdbc, kb, username, false);
    return new Submission(doc, revision, job, duplicate);
  }

  /**
   * Lists permission-scoped resources with explicit pagination bounds.
   *
   * @author agent
   */
  public PageSlice<DocumentView> list(UUID kb, String username, int page, int size) {
    access.requireAccess(kb, username, false);
    bounds(page, size);
    var rows =
        jdbc.query(
            DOC_SQL
                + " WHERE d.knowledge_base_id=? AND NOT d.deleted ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",
            this::document,
            kb,
            size,
            (long) page * size);
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_document WHERE knowledge_base_id=? AND NOT deleted", Long.class, kb);
    return new PageSlice<>(rows, count, page, size);
  }

  /**
   * Returns one resource only within the authenticated knowledge base.
   *
   * @author agent
   */
  public DocumentView get(UUID kb, String username, UUID document) {
    access.requireAccess(kb, username, false);
    var rows =
        jdbc.query(
            DOC_SQL + " WHERE d.knowledge_base_id=? AND d.id=? AND NOT d.deleted", this::document, kb, document);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  /**
   * Returns original bytes from a verified document revision.
   *
   * @author agent
   */
  public byte[] original(UUID kb, String username, UUID document, UUID revision) {
    UUID id = revision(kb, username, document, revision);
    return jdbc.queryForObject(
        "SELECT original FROM rag_document_blob WHERE knowledge_base_id=? AND revision_id=?",
        byte[].class,
        kb,
        id);
  }

  /**
   * Returns exact parsed text or reports that parsing has not completed.
   *
   * @author agent
   */
  public String content(UUID kb, String username, UUID document, UUID revision) {
    UUID id = revision(kb, username, document, revision);
    String text =
        jdbc.queryForObject(
            "SELECT parsed_text FROM rag_document_revision WHERE knowledge_base_id=? AND id=?",
            String.class,
            kb,
            id);
    if (text == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Parsing has not completed");
    return text;
  }

  /**
   * Returns ordered shared chunks with revision-specific source intervals.
   *
   * @author agent
   */
  public PageSlice<ChunkView> chunks(
      UUID kb, String username, UUID document, UUID revision, int page, int size) {
    UUID id = revision(kb, username, document, revision);
    bounds(page, size);
    var rows =
        jdbc.query(
            "SELECT"
                + " c.id,c.content,l.chunk_order,l.tokens,l.token_start,l.token_end,l.source_start,l.source_end,l.headings"
                + " FROM rag_document_chunk l JOIN rag_chunk c ON"
                + " c.knowledge_base_id=l.knowledge_base_id AND c.id=l.chunk_id WHERE"
                + " l.knowledge_base_id=? AND l.revision_id=? ORDER BY l.chunk_order LIMIT ? OFFSET"
                + " ?",
            (rs, n) ->
                ChunkView.builder()
                    .id(rs.getObject("id", UUID.class))
                    .content(rs.getString("content"))
                    .order(rs.getInt("chunk_order"))
                    .tokens(rs.getInt("tokens"))
                    .tokenStart(rs.getInt("token_start"))
                    .tokenEnd(rs.getInt("token_end"))
                    .sourceStart(rs.getInt("source_start"))
                    .sourceEnd(rs.getInt("source_end"))
                    .headings(
                        json.readValue(
                            rs.getString("headings"),
                            json.getTypeFactory()
                                .constructCollectionType(List.class, String.class)))
                    .build(),
            kb,
            id,
            size,
            (long) page * size);
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_document_chunk WHERE knowledge_base_id=? AND revision_id=?",
            Long.class,
            kb,
            id);
    return new PageSlice<>(rows, count, page, size);
  }

  private UUID revision(UUID kb, String username, UUID document, UUID revision) {
    var view = get(kb, username, document);
    UUID id = revision == null ? view.getRevisionId() : revision;
    var metadata =
        revisions
            .findById(new ScopedRevisionId(kb, id))
            .orElseThrow(() -> new RagAccessException(404));
    if (!metadata.getDocumentId().equals(document)) throw new RagAccessException(404);
    return id;
  }

  private DocumentView document(ResultSet rs, int n) throws SQLException {
    return DocumentView.builder()
        .id(rs.getObject("id", UUID.class))
        .sourceName(rs.getString("source_name"))
        .revisionId(rs.getObject("revision_id", UUID.class))
        .jobId(rs.getObject("job_id", UUID.class))
        .format(rs.getString("format"))
        .status(rs.getString("status"))
        .errorCode(rs.getString("error_code"))
        .sizeBytes(rs.getLong("size_bytes"))
        .chunkCount(rs.getLong("chunk_count"))
        .createdAt(rs.getTimestamp("created_at").toInstant())
        .build();
  }

  private void bounds(int page, int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
  }

  private static final String DOC_SQL =
      "SELECT d.id,d.source_name,d.created_at,r.id AS revision_id,r.format,r.size_bytes,j.id AS"
          + " job_id,CASE WHEN d.pipeline_paused THEN 'paused' WHEN d.pipeline_error IS NOT NULL THEN 'failed'"
          + " WHEN j.status<>'processed' THEN j.status WHEN NOT d.pipeline_requested AND EXISTS"
          + " (SELECT 1 FROM rag_knowledge_base b JOIN rag_index_document i ON i.knowledge_base_id=b.id"
          + " AND i.generation_id=b.active_generation_id WHERE b.id=d.knowledge_base_id AND i.document_id=d.id"
          + " AND i.revision_id=d.current_revision_id) THEN 'indexed'"
          + " WHEN d.pipeline_requested AND EXISTS(SELECT 1 FROM rag_document_index_queue q"
          + " JOIN rag_index_generation g ON g.knowledge_base_id=q.knowledge_base_id AND g.id=q.generation_id"
          + " JOIN rag_index_document i ON i.knowledge_base_id=g.knowledge_base_id AND i.generation_id=g.id"
          + " WHERE q.knowledge_base_id=d.knowledge_base_id AND i.document_id=d.id"
          + " AND (q.document_id IS NULL OR q.document_id=d.id)"
          + " AND i.revision_id=d.current_revision_id AND g.state IN ('pending','building','ready')) THEN 'indexing'"
          + " WHEN d.pipeline_requested AND EXISTS(SELECT 1 FROM rag_document_index_queue q"
          + " WHERE q.knowledge_base_id=d.knowledge_base_id AND (q.document_id IS NULL OR q.document_id=d.id))"
          + " THEN 'waiting_index' ELSE 'processed' END AS status,"
          + " COALESCE(d.pipeline_error,j.error_code) AS error_code,(SELECT COUNT(*) FROM rag_document_chunk l WHERE"
          + " l.knowledge_base_id=d.knowledge_base_id AND l.revision_id=r.id) AS chunk_count FROM"
          + " rag_document d JOIN rag_document_revision r ON"
          + " r.knowledge_base_id=d.knowledge_base_id AND r.id=d.current_revision_id JOIN"
          + " rag_ingestion_job j ON j.knowledge_base_id=d.knowledge_base_id AND"
          + " j.revision_id=r.id";

  /**
   * Computes a stable SHA-256 content fingerprint.
   *
   * @author agent
   */
  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
