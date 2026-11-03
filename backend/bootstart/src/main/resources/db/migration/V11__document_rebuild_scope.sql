ALTER TABLE rag_document ADD COLUMN pipeline_rebuild BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN rag_document.pipeline_rebuild IS '仅此文档显式重建时重新抽取，运行可复用同协议同模型空间的完整分块检查点';
ALTER TABLE rag_document_index_queue ADD COLUMN document_id UUID;
ALTER TABLE rag_document_index_queue ADD CONSTRAINT rag_document_queue_scope_fk
    FOREIGN KEY (knowledge_base_id,document_id) REFERENCES rag_document(knowledge_base_id,id);
COMMENT ON COLUMN rag_document_index_queue.document_id IS '行操作目标文档，空值表示自动上传队列；非空时仅对目标执行新模型抽取';
