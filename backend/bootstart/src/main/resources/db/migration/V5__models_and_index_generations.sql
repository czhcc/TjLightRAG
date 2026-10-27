-- Additive model configuration and isolated, immutable index generations.

ALTER TABLE app_user ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app_user.is_admin IS '是否允许管理平台模型配置';

UPDATE app_user SET is_admin = TRUE WHERE username = 'admin';

CREATE TABLE rag_model_profile (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE CHECK (BTRIM(name) <> ''),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CHAT','EMBEDDING','RERANK')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE rag_model_profile IS '平台模型配置';

COMMENT ON COLUMN rag_model_profile.id IS '配置标识';

COMMENT ON COLUMN rag_model_profile.name IS '配置显示名称';

COMMENT ON COLUMN rag_model_profile.kind IS '模型协议用途';

COMMENT ON COLUMN rag_model_profile.created_at IS '创建时间';

CREATE TABLE rag_model_version (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES rag_model_profile(id),
    version INTEGER NOT NULL CHECK (version > 0),
    base_url VARCHAR(1000) NOT NULL,
    model VARCHAR(256) NOT NULL,
    secret_cipher TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 0 AND 2000),
    context_tokens INTEGER NOT NULL CHECK (context_tokens BETWEEN 1024 AND 1000000),
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens BETWEEN 64 AND 32000),
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (profile_id, version)
);

COMMENT ON TABLE rag_model_version IS '不可变模型版本 密钥不回传';

COMMENT ON COLUMN rag_model_version.id IS '不可变版本标识';

COMMENT ON COLUMN rag_model_version.profile_id IS '所属配置';

COMMENT ON COLUMN rag_model_version.version IS '递增版本号';

COMMENT ON COLUMN rag_model_version.base_url IS '兼容服务基础地址';

COMMENT ON COLUMN rag_model_version.model IS '供应商模型标识';

COMMENT ON COLUMN rag_model_version.secret_cipher IS 'AES GCM 加密密钥或空字符串';

COMMENT ON COLUMN rag_model_version.dimensions IS '向量维度 非向量为零';

COMMENT ON COLUMN rag_model_version.context_tokens IS '输入输出总上下文上限';

COMMENT ON COLUMN rag_model_version.max_output_tokens IS '最大生成输出 token';

COMMENT ON COLUMN rag_model_version.timeout_seconds IS '单次调用超时秒';

COMMENT ON COLUMN rag_model_version.created_at IS '版本创建时间';

CREATE FUNCTION rag_reject_model_version_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Model versions are immutable'; END $$;

CREATE TRIGGER rag_model_version_immutable BEFORE UPDATE ON rag_model_version FOR EACH ROW EXECUTE FUNCTION rag_reject_model_version_update();

CREATE TABLE rag_model_binding (
    role VARCHAR(16) PRIMARY KEY CHECK (role IN ('extract','summary','keywords','answer','embedding','rerank')),
    version_id UUID NOT NULL REFERENCES rag_model_version(id)
);

COMMENT ON TABLE rag_model_binding IS '新构建使用的角色配置 不修改旧索引';

COMMENT ON COLUMN rag_model_binding.role IS '模型调用角色';

COMMENT ON COLUMN rag_model_binding.version_id IS '绑定的不可变版本';

CREATE TABLE rag_index_generation (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','building','ready','failed','cancelled')),
    requested_by BIGINT NOT NULL REFERENCES app_user(id),
    extract_version UUID NOT NULL REFERENCES rag_model_version(id),
    summary_version UUID NOT NULL REFERENCES rag_model_version(id),
    embedding_version UUID NOT NULL REFERENCES rag_model_version(id),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 2000),
    prompt_version VARCHAR(100) NOT NULL,
    tokenizer VARCHAR(100) NOT NULL,
    gleaning INTEGER NOT NULL DEFAULT 1 CHECK (gleaning BETWEEN 0 AND 1),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (knowledge_base_id,id),
    UNIQUE (knowledge_base_id,id,dimensions)
);

COMMENT ON TABLE rag_index_generation IS '独立模型空间和索引构建任务';

COMMENT ON COLUMN rag_index_generation.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_generation.id IS '索引代标识';

COMMENT ON COLUMN rag_index_generation.state IS '构建状态 与当前激活指针分离';

COMMENT ON COLUMN rag_index_generation.requested_by IS '发起构建用户';

COMMENT ON COLUMN rag_index_generation.extract_version IS '抽取模型版本';

COMMENT ON COLUMN rag_index_generation.summary_version IS '摘要模型版本';

COMMENT ON COLUMN rag_index_generation.embedding_version IS '向量模型版本';

COMMENT ON COLUMN rag_index_generation.dimensions IS '本代固定向量维度';

COMMENT ON COLUMN rag_index_generation.prompt_version IS '提示词版本';

COMMENT ON COLUMN rag_index_generation.tokenizer IS '预算 tokenizer';

COMMENT ON COLUMN rag_index_generation.gleaning IS '有界补充抽取次数';

COMMENT ON COLUMN rag_index_generation.attempts IS '已领取次数';

COMMENT ON COLUMN rag_index_generation.lease_token IS '防止过期 worker 发布的令牌';

COMMENT ON COLUMN rag_index_generation.lease_until IS '租约截止时间';

COMMENT ON COLUMN rag_index_generation.cancel_requested IS '持久化取消请求';

COMMENT ON COLUMN rag_index_generation.error_code IS '脱敏失败码';

COMMENT ON COLUMN rag_index_generation.created_at IS '构建创建时间';

COMMENT ON COLUMN rag_index_generation.completed_at IS '成功完成时间';

ALTER TABLE rag_knowledge_base ADD COLUMN active_generation_id UUID;

COMMENT ON COLUMN rag_knowledge_base.active_generation_id IS '当前完整可用索引代 仅通过原子激活更新';

ALTER TABLE rag_knowledge_base ADD CONSTRAINT rag_active_generation_fk FOREIGN KEY (id,active_generation_id) REFERENCES rag_index_generation(knowledge_base_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE rag_index_document (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    PRIMARY KEY (knowledge_base_id,generation_id,document_id),
    FOREIGN KEY (knowledge_base_id,generation_id) REFERENCES rag_index_generation(knowledge_base_id,id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,revision_id) REFERENCES rag_document_revision(knowledge_base_id,id)
);

COMMENT ON TABLE rag_index_document IS '构建时文档来源快照';

COMMENT ON COLUMN rag_index_document.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_document.generation_id IS '索引代';

COMMENT ON COLUMN rag_index_document.document_id IS '逻辑文档';

COMMENT ON COLUMN rag_index_document.revision_id IS '构建捕获的不可变修订';

CREATE TABLE rag_index_chunk (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    extraction JSONB,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (knowledge_base_id,generation_id,chunk_id),
    FOREIGN KEY (knowledge_base_id,generation_id) REFERENCES rag_index_generation(knowledge_base_id,id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,chunk_id) REFERENCES rag_chunk(knowledge_base_id,id) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE rag_index_chunk IS '按共享块一次抽取 可重试恢复';

COMMENT ON COLUMN rag_index_chunk.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_chunk.generation_id IS '索引代';

COMMENT ON COLUMN rag_index_chunk.chunk_id IS '共享块标识';

COMMENT ON COLUMN rag_index_chunk.extraction IS '逐块贡献检查点 不保存完整模型响应';

COMMENT ON COLUMN rag_index_chunk.completed IS '贡献与块向量已原子保存';

CREATE TABLE rag_index_entity (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    id UUID NOT NULL,
    name VARCHAR(512) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    source_ids JSONB NOT NULL,
    PRIMARY KEY (knowledge_base_id,generation_id,id),
    UNIQUE (knowledge_base_id,generation_id,name),
    FOREIGN KEY (knowledge_base_id,generation_id) REFERENCES rag_index_generation(knowledge_base_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_index_entity IS '索引代内图节点';

COMMENT ON COLUMN rag_index_entity.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_entity.generation_id IS '索引代';

COMMENT ON COLUMN rag_index_entity.id IS '本代节点标识';

COMMENT ON COLUMN rag_index_entity.name IS '归一实体名';

COMMENT ON COLUMN rag_index_entity.entity_type IS '合并后类型';

COMMENT ON COLUMN rag_index_entity.description IS '去重或摘要后的描述';

COMMENT ON COLUMN rag_index_entity.source_ids IS '完整贡献块标识';

CREATE TABLE rag_index_relation (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    id UUID NOT NULL,
    source_id UUID NOT NULL,
    target_id UUID NOT NULL,
    description TEXT NOT NULL,
    keywords TEXT NOT NULL,
    weight DOUBLE PRECISION NOT NULL CHECK (weight > 0 AND weight < 'Infinity'::DOUBLE PRECISION),
    source_ids JSONB NOT NULL,
    PRIMARY KEY (knowledge_base_id,generation_id,id),
    UNIQUE (knowledge_base_id,generation_id,source_id,target_id),
    CHECK (source_id < target_id),
    FOREIGN KEY (knowledge_base_id,generation_id,source_id) REFERENCES rag_index_entity(knowledge_base_id,generation_id,id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,generation_id,target_id) REFERENCES rag_index_entity(knowledge_base_id,generation_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_index_relation IS '无向关系及逐块来源';

COMMENT ON COLUMN rag_index_relation.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_relation.generation_id IS '索引代';

COMMENT ON COLUMN rag_index_relation.id IS '本代关系标识';

COMMENT ON COLUMN rag_index_relation.source_id IS '首端点';

COMMENT ON COLUMN rag_index_relation.target_id IS '末端点';

COMMENT ON COLUMN rag_index_relation.description IS '去重或摘要后的描述';

COMMENT ON COLUMN rag_index_relation.keywords IS '去重关系关键词';

COMMENT ON COLUMN rag_index_relation.weight IS '每块一次贡献累加权重';

COMMENT ON COLUMN rag_index_relation.source_ids IS '完整贡献块标识';

CREATE TABLE rag_index_chunk_vector (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    object_id UUID NOT NULL,
    dimensions INTEGER NOT NULL,
    embedding public.vector NOT NULL CHECK (public.vector_dims(embedding)=dimensions AND public.vector_norm(embedding)>0),
    PRIMARY KEY (knowledge_base_id,generation_id,object_id),
    FOREIGN KEY (knowledge_base_id,generation_id,dimensions) REFERENCES rag_index_generation(knowledge_base_id,id,dimensions) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,generation_id,object_id) REFERENCES rag_index_chunk(knowledge_base_id,generation_id,chunk_id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_index_chunk_vector IS 'chunk 独立索引代向量集合';

COMMENT ON COLUMN rag_index_chunk_vector.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_chunk_vector.generation_id IS '索引代 模型空间隔离';

COMMENT ON COLUMN rag_index_chunk_vector.object_id IS '向量对象标识';

COMMENT ON COLUMN rag_index_chunk_vector.dimensions IS '捕获的模型维度';

COMMENT ON COLUMN rag_index_chunk_vector.embedding IS '校验维度的有限非零向量';

CREATE TABLE rag_index_entity_vector (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    object_id UUID NOT NULL,
    dimensions INTEGER NOT NULL,
    embedding public.vector NOT NULL CHECK (public.vector_dims(embedding)=dimensions AND public.vector_norm(embedding)>0),
    PRIMARY KEY (knowledge_base_id,generation_id,object_id),
    FOREIGN KEY (knowledge_base_id,generation_id,dimensions) REFERENCES rag_index_generation(knowledge_base_id,id,dimensions) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,generation_id,object_id) REFERENCES rag_index_entity(knowledge_base_id,generation_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_index_entity_vector IS 'entity 独立索引代向量集合';

COMMENT ON COLUMN rag_index_entity_vector.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_entity_vector.generation_id IS '索引代 模型空间隔离';

COMMENT ON COLUMN rag_index_entity_vector.object_id IS '向量对象标识';

COMMENT ON COLUMN rag_index_entity_vector.dimensions IS '捕获的模型维度';

COMMENT ON COLUMN rag_index_entity_vector.embedding IS '校验维度的有限非零向量';

CREATE TABLE rag_index_relation_vector (
    knowledge_base_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    object_id UUID NOT NULL,
    dimensions INTEGER NOT NULL,
    embedding public.vector NOT NULL CHECK (public.vector_dims(embedding)=dimensions AND public.vector_norm(embedding)>0),
    PRIMARY KEY (knowledge_base_id,generation_id,object_id),
    FOREIGN KEY (knowledge_base_id,generation_id,dimensions) REFERENCES rag_index_generation(knowledge_base_id,id,dimensions) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id,generation_id,object_id) REFERENCES rag_index_relation(knowledge_base_id,generation_id,id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_index_relation_vector IS 'relation 独立索引代向量集合';

COMMENT ON COLUMN rag_index_relation_vector.knowledge_base_id IS '知识库边界';

COMMENT ON COLUMN rag_index_relation_vector.generation_id IS '索引代 模型空间隔离';

COMMENT ON COLUMN rag_index_relation_vector.object_id IS '向量对象标识';

COMMENT ON COLUMN rag_index_relation_vector.dimensions IS '捕获的模型维度';

COMMENT ON COLUMN rag_index_relation_vector.embedding IS '校验维度的有限非零向量';

CREATE INDEX rag_index_pending ON rag_index_generation (state,created_at);

ALTER TABLE rag_index_generation ADD COLUMN retry_at TIMESTAMPTZ;
COMMENT ON COLUMN rag_index_generation.retry_at IS '有限自动重试的下次时间 空为需手动重试';
