ALTER TABLE rag_chunk ADD COLUMN content_hash VARCHAR(64);
COMMENT ON COLUMN rag_chunk.content_hash IS '不可变正文的 SHA256，旧版原始块为空';
ALTER TABLE rag_chunk ADD CONSTRAINT rag_chunk_hash_unique UNIQUE (knowledge_base_id, content_hash);
ALTER TABLE rag_chunk ADD CONSTRAINT rag_chunk_hash_matches CHECK (content_hash IS NULL OR content_hash = ENCODE(SHA256(CONVERT_TO(content, 'UTF8')), 'hex'));

CREATE TABLE rag_document (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id),
    UNIQUE (knowledge_base_id, source_name)
);
COMMENT ON TABLE rag_document IS '按知识库和逻辑来源去重的文档';
COMMENT ON COLUMN rag_document.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_document.id IS '文档标识';
COMMENT ON COLUMN rag_document.source_name IS '逻辑来源名称，不作为文件系统路径';
COMMENT ON COLUMN rag_document.current_revision_id IS '当前修订标识';
COMMENT ON COLUMN rag_document.created_at IS '创建时间';

CREATE TABLE rag_document_revision (
    knowledge_base_id UUID NOT NULL,
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_key VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    format VARCHAR(16) NOT NULL CHECK (format IN ('text', 'markdown')),
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
    tokenizer VARCHAR(64) NOT NULL,
    chunk_strategy VARCHAR(64) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunk_size INTEGER NOT NULL CHECK (chunk_size BETWEEN 16 AND 4096),
    overlap INTEGER NOT NULL CHECK (overlap >= 0 AND overlap <= chunk_size / 2),
    parsed_text TEXT,
    char_count INTEGER,
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id),
    UNIQUE (knowledge_base_id, document_id, revision_key),
    UNIQUE (knowledge_base_id, document_id, id),
    FOREIGN KEY (knowledge_base_id, document_id) REFERENCES rag_document(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_document_revision IS '不可变文档输入与解析检查点';
COMMENT ON COLUMN rag_document_revision.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_document_revision.id IS '修订标识';
COMMENT ON COLUMN rag_document_revision.document_id IS '文档标识';
COMMENT ON COLUMN rag_document_revision.revision_key IS '原文与解析分块配置的联合 SHA256';
COMMENT ON COLUMN rag_document_revision.content_hash IS '原文字节 SHA256';
COMMENT ON COLUMN rag_document_revision.format IS '纯文本或 Markdown';
COMMENT ON COLUMN rag_document_revision.size_bytes IS '原文字节长度';
COMMENT ON COLUMN rag_document_revision.tokenizer IS '明确的 tokenizer 身份';
COMMENT ON COLUMN rag_document_revision.chunk_strategy IS '有版本的分块策略';
COMMENT ON COLUMN rag_document_revision.parser_version IS '有版本的解析策略';
COMMENT ON COLUMN rag_document_revision.chunk_size IS '最大 token 窗口';
COMMENT ON COLUMN rag_document_revision.overlap IS '期望重叠 token 数';
COMMENT ON COLUMN rag_document_revision.parsed_text IS '精确保留的解码正文检查点';
COMMENT ON COLUMN rag_document_revision.char_count IS '解析正文 Unicode 码点数';
COMMENT ON COLUMN rag_document_revision.created_by IS '初始上传用户';
COMMENT ON COLUMN rag_document_revision.created_at IS '创建时间';

ALTER TABLE rag_document ADD CONSTRAINT rag_document_current_revision FOREIGN KEY (knowledge_base_id, id, current_revision_id) REFERENCES rag_document_revision(knowledge_base_id, document_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE rag_document_blob (
    knowledge_base_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    original BYTEA NOT NULL CHECK (OCTET_LENGTH(original) BETWEEN 1 AND 5242880),
    PRIMARY KEY (knowledge_base_id, revision_id),
    FOREIGN KEY (knowledge_base_id, revision_id) REFERENCES rag_document_revision(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_document_blob IS '修订原文，不依赖容器本地目录';
COMMENT ON COLUMN rag_document_blob.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_document_blob.revision_id IS '修订标识';
COMMENT ON COLUMN rag_document_blob.original IS '不可变原文，最多五 MiB';

CREATE TABLE rag_document_chunk (
    knowledge_base_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    chunk_order INTEGER NOT NULL CHECK (chunk_order >= 0),
    chunk_id UUID NOT NULL,
    tokens INTEGER NOT NULL CHECK (tokens > 0),
    token_start INTEGER NOT NULL CHECK (token_start >= 0),
    token_end INTEGER NOT NULL CHECK (token_end > token_start),
    source_start INTEGER NOT NULL CHECK (source_start >= 0),
    source_end INTEGER NOT NULL CHECK (source_end > source_start),
    headings JSONB NOT NULL DEFAULT '[]' CHECK (JSONB_TYPEOF(headings) = 'array'),
    PRIMARY KEY (knowledge_base_id, revision_id, chunk_order),
    CHECK (tokens = token_end - token_start),
    FOREIGN KEY (knowledge_base_id, revision_id) REFERENCES rag_document_revision(knowledge_base_id, id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id, chunk_id) REFERENCES rag_chunk(knowledge_base_id, id)
);
COMMENT ON TABLE rag_document_chunk IS '文档修订与共享正文块的有序来源关联';
COMMENT ON COLUMN rag_document_chunk.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_document_chunk.revision_id IS '修订标识';
COMMENT ON COLUMN rag_document_chunk.chunk_order IS '块顺序，从零开始';
COMMENT ON COLUMN rag_document_chunk.chunk_id IS '可跨文档共享的块标识';
COMMENT ON COLUMN rag_document_chunk.tokens IS '窗口 token 数，包含去除边缘空白前的 token';
COMMENT ON COLUMN rag_document_chunk.token_start IS '半开 token 区间起点';
COMMENT ON COLUMN rag_document_chunk.token_end IS '半开 token 区间终点';
COMMENT ON COLUMN rag_document_chunk.source_start IS '解析正文的半开 Unicode 码点区间起点';
COMMENT ON COLUMN rag_document_chunk.source_end IS '解析正文的半开 Unicode 码点区间终点';
COMMENT ON COLUMN rag_document_chunk.headings IS '章节路径';

CREATE INDEX rag_document_chunk_shared ON rag_document_chunk (knowledge_base_id, chunk_id);

CREATE TABLE rag_ingestion_job (
    knowledge_base_id UUID NOT NULL,
    id UUID NOT NULL,
    revision_id UUID NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES app_user(id),
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'parsing', 'processing', 'processed', 'failed', 'cancelled')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    lease_token UUID,
    lease_owner VARCHAR(100),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id),
    UNIQUE (knowledge_base_id, revision_id),
    FOREIGN KEY (knowledge_base_id, revision_id) REFERENCES rag_document_revision(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_ingestion_job IS '可租约恢复并有界重试的持久化预处理任务';
COMMENT ON COLUMN rag_ingestion_job.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_ingestion_job.id IS '任务标识';
COMMENT ON COLUMN rag_ingestion_job.revision_id IS '输入修订标识';
COMMENT ON COLUMN rag_ingestion_job.requested_by IS '本次执行所检查的用户，手动重试时更新';
COMMENT ON COLUMN rag_ingestion_job.status IS '阶段状态，processed 仅表示本阶段预处理完成';
COMMENT ON COLUMN rag_ingestion_job.attempts IS '当前手动周期内已领取次数';
COMMENT ON COLUMN rag_ingestion_job.max_attempts IS '自动尝试上限';
COMMENT ON COLUMN rag_ingestion_job.cancel_requested IS '持久化取消请求';
COMMENT ON COLUMN rag_ingestion_job.lease_token IS '当前 worker 的 fencing token';
COMMENT ON COLUMN rag_ingestion_job.lease_owner IS 'worker 标识，不作为权限依据';
COMMENT ON COLUMN rag_ingestion_job.lease_until IS '租约过期时间';
COMMENT ON COLUMN rag_ingestion_job.next_attempt_at IS '可重试失败的下一次时间';
COMMENT ON COLUMN rag_ingestion_job.error_code IS '脱敏错误码';
COMMENT ON COLUMN rag_ingestion_job.created_at IS '创建时间';
COMMENT ON COLUMN rag_ingestion_job.updated_at IS '最后状态时间';

CREATE INDEX rag_job_claim ON rag_ingestion_job (status, next_attempt_at, lease_until, created_at);

CREATE TABLE rag_job_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    job_id UUID NOT NULL,
    event VARCHAR(40) NOT NULL,
    code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (knowledge_base_id, job_id) REFERENCES rag_ingestion_job(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_job_event IS '脱敏的任务阶段事件';
COMMENT ON COLUMN rag_job_event.id IS '事件顺序';
COMMENT ON COLUMN rag_job_event.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_job_event.job_id IS '任务标识';
COMMENT ON COLUMN rag_job_event.event IS '事件类别';
COMMENT ON COLUMN rag_job_event.code IS '可选脱敏错误码';
COMMENT ON COLUMN rag_job_event.created_at IS '事件时间';

CREATE INDEX rag_job_event_lookup ON rag_job_event (knowledge_base_id, job_id, id);
