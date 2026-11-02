ALTER TABLE rag_document ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_document ADD COLUMN pipeline_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_document ADD COLUMN pipeline_paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_document ADD COLUMN pipeline_error VARCHAR(64);
COMMENT ON COLUMN rag_document.deleted IS '逻辑删除，不再参与列表和新索引，保留旧代来源完整性';
COMMENT ON COLUMN rag_document.pipeline_requested IS '当前修订等待自动索引构建和激活';
COMMENT ON COLUMN rag_document.pipeline_paused IS '用户暂停此文档的分块或索引处理';
COMMENT ON COLUMN rag_document.pipeline_error IS '自动流程脱敏错误码';

ALTER TABLE rag_index_generation ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN rag_index_generation.automatic IS '文档自动流程创建的索引代，执行用户须保留写权限';

CREATE SEQUENCE rag_document_pipeline_revision;
COMMENT ON SEQUENCE rag_document_pipeline_revision IS '跨队列生命周期单调递增的文档操作序号';
CREATE TABLE rag_document_index_queue (
    knowledge_base_id UUID PRIMARY KEY REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    requested_by BIGINT NOT NULL REFERENCES app_user(id),
    generation_id UUID,
    expected_generation_id UUID,
    dirty BOOLEAN NOT NULL DEFAULT TRUE,
    refresh_required BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT NEXTVAL('rag_document_pipeline_revision'),
    FOREIGN KEY (knowledge_base_id,generation_id) REFERENCES rag_index_generation(knowledge_base_id,id)
);
COMMENT ON TABLE rag_document_index_queue IS '文档自动索引协调队列，每知识库串行构建和比较切换';
COMMENT ON COLUMN rag_document_index_queue.knowledge_base_id IS '知识库边界和协调锁';
COMMENT ON COLUMN rag_document_index_queue.requested_by IS '实际请求用户，后台执行重新检查写权限';
COMMENT ON COLUMN rag_document_index_queue.generation_id IS '当前自动构建索引代';
COMMENT ON COLUMN rag_document_index_queue.expected_generation_id IS '构建前捕获的当前索引代，用于乐观切换';
COMMENT ON COLUMN rag_document_index_queue.dirty IS '构建期间输入或文档控制发生变化，旧构建不得自动发布';
COMMENT ON COLUMN rag_document_index_queue.refresh_required IS '删除文档后即使无新修订也需更新索引';
COMMENT ON COLUMN rag_document_index_queue.error_code IS '协调错误码，运行操作清除后重试';
COMMENT ON COLUMN rag_document_index_queue.revision IS '控制操作序号，旧协调失败不得覆盖新请求';

UPDATE rag_document d SET pipeline_requested=TRUE WHERE NOT EXISTS (
    SELECT 1 FROM rag_knowledge_base b JOIN rag_index_document i
    ON i.knowledge_base_id=b.id AND i.generation_id=b.active_generation_id
    WHERE b.id=d.knowledge_base_id AND i.document_id=d.id AND i.revision_id=d.current_revision_id
);
INSERT INTO rag_document_index_queue(knowledge_base_id,requested_by)
SELECT DISTINCT ON (d.knowledge_base_id) d.knowledge_base_id,j.requested_by
FROM rag_document d JOIN rag_ingestion_job j
ON j.knowledge_base_id=d.knowledge_base_id AND j.revision_id=d.current_revision_id
WHERE d.pipeline_requested ORDER BY d.knowledge_base_id,j.created_at DESC;
