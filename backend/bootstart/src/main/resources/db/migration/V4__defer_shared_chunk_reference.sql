-- Keep shared chunks protected, while allowing a complete knowledge-base cascade
-- to remove both the chunks and their document associations in either trigger order.
ALTER TABLE rag_document_chunk
    ALTER CONSTRAINT rag_document_chunk_knowledge_base_id_chunk_id_fkey
    DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT rag_document_chunk_knowledge_base_id_chunk_id_fkey ON rag_document_chunk
    IS '共享块引用在提交时校验，允许整库级联清理但禁止遗留悬空来源';
