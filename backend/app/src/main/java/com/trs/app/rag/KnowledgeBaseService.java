package com.trs.app.rag;

import com.trs.app.UserRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge-base lifecycle and authorization, without RAG indexing logic.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KnowledgeBaseService {
  private final KnowledgeBaseRepository bases;
  private final MembershipRepository members;
  private final UserRepository users;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  /**
   * Creates a base and owner membership atomically.
   *
   * @param username authenticated principal
   * @param name display name
   * @param description summary
   * @return owned base
   * @author agent
   */
  public KnowledgeBaseView create(String username, String name, String description) {
    validate(name, description);
    var user = users.findByUsername(username).orElseThrow(() -> new RagAccessException(404));
    var base = new KnowledgeBase();
    base.setId(UUID.randomUUID());
    base.setName(name.strip());
    base.setDescription(description == null ? "" : description);
    base.setCreatedAt(Instant.now());
    bases.save(base);
    var membership = new KnowledgeBaseMember();
    membership.setId(UUID.randomUUID());
    membership.setKnowledgeBase(base);
    membership.setUser(user);
    membership.setRole(KnowledgeRole.OWNER);
    members.save(membership);
    return view(base, KnowledgeRole.OWNER);
  }

  /**
   * Returns a paginated membership-scoped list.
   *
   * @param username authenticated principal
   * @param page zero-based page
   * @param size page size, 1..100
   * @return visible bases
   * @author agent
   */
  public List<KnowledgeBaseView> list(String username, int page, int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
    return bases.visibleTo(username, PageRequest.of(page, size)).stream()
        .map(b -> view(b, requireAccess(b.getId(), username, false)))
        .toList();
  }

  /**
   * Reads one visible base.
   *
   * @param id base identifier
   * @param username principal
   * @return scoped view
   * @author agent
   */
  public KnowledgeBaseView get(UUID id, String username) {
    var role = requireAccess(id, username, false);
    return view(bases.findById(id).orElseThrow(() -> new RagAccessException(404)), role);
  }

  /**
   * Updates metadata as owner.
   *
   * @param id base identifier
   * @param username principal
   * @param name display name
   * @param description summary
   * @return updated base
   * @author agent
   */
  public KnowledgeBaseView update(UUID id, String username, String name, String description) {
    owner(id, username);
    validate(name, description);
    var base = bases.findById(id).orElseThrow(() -> new RagAccessException(404));
    base.setName(name.strip());
    base.setDescription(description == null ? "" : description);
    return view(base, KnowledgeRole.OWNER);
  }

  /**
   * Deletes a whole base, with database cascades scoped by base identifier.
   *
   * @param id base identifier
   * @param username principal
   * @author agent
   */
  public void delete(UUID id, String username) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "rag-kb:" + id);
    owner(id, username);
    bases.deleteById(id);
  }

  /**
   * Adds or changes a reader/writer membership; ownership is immutable here.
   *
   * @param id base identifier
   * @param username owner principal
   * @param target existing username
   * @param role reader/writer role
   * @author agent
   */
  public void grant(UUID id, String username, String target, KnowledgeRole role) {
    owner(id, username);
    if (role == null || role == KnowledgeRole.OWNER || target.equals(username))
      throw new IllegalArgumentException("Ownership changes are unsupported");
    var user = users.findByUsername(target).orElseThrow(() -> new RagAccessException(404));
    var member =
        members
            .findByKnowledgeBase_IdAndUser_Username(id, target)
            .orElseGet(
                () -> {
                  var m = new KnowledgeBaseMember();
                  m.setId(UUID.randomUUID());
                  m.setKnowledgeBase(bases.getReferenceById(id));
                  m.setUser(user);
                  return m;
                });
    member.setRole(role);
    members.save(member);
  }

  /**
   * Revokes a non-owner membership.
   *
   * @param id base identifier
   * @param username owner principal
   * @param target member username
   * @author agent
   */
  public void revoke(UUID id, String username, String target) {
    owner(id, username);
    if (target.equals(username)) throw new IllegalArgumentException("Cannot revoke the owner");
    members
        .findByKnowledgeBase_IdAndUser_Username(id, target)
        .ifPresent(
            m -> {
              if (m.getRole() == KnowledgeRole.OWNER)
                throw new IllegalArgumentException("Cannot revoke the owner");
              members.delete(m);
            });
  }

  /**
   * Checks access at both service and storage entry points; lock lasts until transaction end.
   *
   * @param id base identifier
   * @param username principal
   * @param write requires write permission
   * @return current role
   * @author agent
   */
  public KnowledgeRole requireAccess(UUID id, String username, boolean write) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock_shared(hashtextextended(?,0))", "rag-kb:" + id);
    var member =
        members
            .findByKnowledgeBase_IdAndUser_Username(id, username)
            .orElseThrow(() -> new RagAccessException(404));
    if (write && member.getRole() == KnowledgeRole.READER) throw new RagAccessException(403);
    return member.getRole();
  }

  private void owner(UUID id, String username) {
    if (requireAccess(id, username, false) != KnowledgeRole.OWNER)
      throw new RagAccessException(403);
  }

  private void validate(String name, String description) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || (description != null && description.length() > 4000))
      throw new IllegalArgumentException("Invalid metadata");
  }

  private KnowledgeBaseView view(KnowledgeBase base, KnowledgeRole role) {
    return new KnowledgeBaseView(base.getId(), base.getName(), base.getDescription(), role);
  }
}
