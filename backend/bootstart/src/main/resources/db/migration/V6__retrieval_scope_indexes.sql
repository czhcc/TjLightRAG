-- Exact retrieval remains partitioned by the captured knowledge base and index generation.
CREATE INDEX rag_index_relation_target_lookup ON rag_index_relation (knowledge_base_id, generation_id, target_id);
COMMENT ON INDEX rag_index_relation_target_lookup IS '补充关系首端点唯一索引 支持末端点邻域与度数查询';
CREATE INDEX rag_index_document_revision_lookup ON rag_index_document (knowledge_base_id, generation_id, revision_id);
COMMENT ON INDEX rag_index_document_revision_lookup IS '按索引代捕获的修订解析来源 不读取当前修订';
