package com.trs.app.rag.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Standard JPA metadata access; native SQL is reserved for atomic acceptance and fenced queue
 * updates.
 *
 * @author agent
 */
public interface RevisionRepository extends JpaRepository<StoredRevision, ScopedRevisionId> {}
