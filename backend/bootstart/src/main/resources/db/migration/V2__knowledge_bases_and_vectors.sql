-- PostgreSQL 18 + pgvector; placeholders are validated before application migration.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE rag_vector_configuration (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 2000),
    model_id VARCHAR(128) NOT NULL
);
COMMENT ON TABLE rag_vector_configuration IS '不可变的单模型向量空间配置';
COMMENT ON COLUMN rag_vector_configuration.id IS '单例标识';
COMMENT ON COLUMN rag_vector_configuration.dimensions IS '固定向量维度';
COMMENT ON COLUMN rag_vector_configuration.model_id IS '非秘密模型标识';

INSERT INTO rag_vector_configuration (id, dimensions, model_id) VALUES (1, ${ragVectorDimensions}, '${ragEmbeddingModel}');

CREATE TABLE rag_knowledge_base (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (BTRIM(name) <> ''),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE rag_knowledge_base IS '知识库元数据';
COMMENT ON COLUMN rag_knowledge_base.id IS '知识库标识';
COMMENT ON COLUMN rag_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN rag_knowledge_base.description IS '知识库说明';
COMMENT ON COLUMN rag_knowledge_base.created_at IS '创建时间';

CREATE TABLE rag_knowledge_base_member (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'WRITER', 'READER')),
    UNIQUE (knowledge_base_id, user_id)
);
COMMENT ON TABLE rag_knowledge_base_member IS '知识库授权成员';
COMMENT ON COLUMN rag_knowledge_base_member.id IS '成员记录标识';
COMMENT ON COLUMN rag_knowledge_base_member.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_knowledge_base_member.user_id IS '用户标识';
COMMENT ON COLUMN rag_knowledge_base_member.role IS '所有者或写入或只读角色';

CREATE UNIQUE INDEX rag_knowledge_base_single_owner ON rag_knowledge_base_member (knowledge_base_id) WHERE role = 'OWNER';
CREATE INDEX rag_member_user_base ON rag_knowledge_base_member (user_id, knowledge_base_id);

CREATE TABLE rag_chunk (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    content TEXT NOT NULL CHECK (BTRIM(content) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id)
);
COMMENT ON TABLE rag_chunk IS '知识库文本块原始存储';
COMMENT ON COLUMN rag_chunk.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_chunk.id IS '块标识';
COMMENT ON COLUMN rag_chunk.content IS '块正文';
COMMENT ON COLUMN rag_chunk.created_at IS '创建时间';

CREATE TABLE rag_entity (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    name VARCHAR(512) NOT NULL CHECK (BTRIM(name) <> ''),
    entity_type VARCHAR(100) NOT NULL CHECK (BTRIM(entity_type) <> ''),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id),
    UNIQUE (knowledge_base_id, name)
);
COMMENT ON TABLE rag_entity IS '知识库图节点原始存储';
COMMENT ON COLUMN rag_entity.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_entity.id IS '实体标识';
COMMENT ON COLUMN rag_entity.name IS '已规范化实体名';
COMMENT ON COLUMN rag_entity.entity_type IS '实体类型';
COMMENT ON COLUMN rag_entity.description IS '实体描述';
COMMENT ON COLUMN rag_entity.created_at IS '创建时间';

CREATE TABLE rag_relation (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    source_entity_id UUID NOT NULL,
    target_entity_id UUID NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    weight DOUBLE PRECISION NOT NULL CHECK (weight >= 0 AND weight < 'Infinity'::DOUBLE PRECISION),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, id),
    UNIQUE (knowledge_base_id, source_entity_id, target_entity_id),
    CHECK (source_entity_id < target_entity_id),
    FOREIGN KEY (knowledge_base_id, source_entity_id) REFERENCES rag_entity(knowledge_base_id, id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id, target_entity_id) REFERENCES rag_entity(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_relation IS '知识库无向图关系原始存储';
COMMENT ON COLUMN rag_relation.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_relation.id IS '关系标识';
COMMENT ON COLUMN rag_relation.source_entity_id IS '规范顺序的首端点标识';
COMMENT ON COLUMN rag_relation.target_entity_id IS '规范顺序的末端点标识';
COMMENT ON COLUMN rag_relation.description IS '关系描述';
COMMENT ON COLUMN rag_relation.weight IS '有限非负关系权重';
COMMENT ON COLUMN rag_relation.created_at IS '创建时间';

CREATE INDEX rag_relation_target ON rag_relation (knowledge_base_id, target_entity_id);

CREATE TABLE rag_chunk_embedding (
    knowledge_base_id UUID NOT NULL,
    object_id UUID NOT NULL,
    embedding public.vector(${ragVectorDimensions}) NOT NULL CHECK (public.vector_norm(embedding) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, object_id),
    FOREIGN KEY (knowledge_base_id, object_id) REFERENCES rag_chunk(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_chunk_embedding IS '文本块独立向量集合';
COMMENT ON COLUMN rag_chunk_embedding.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_chunk_embedding.object_id IS '文本块标识';
COMMENT ON COLUMN rag_chunk_embedding.embedding IS '固定维度的有限非零向量';
COMMENT ON COLUMN rag_chunk_embedding.updated_at IS '最近写入时间';

CREATE INDEX rag_chunk_embedding_hnsw ON rag_chunk_embedding USING hnsw (embedding public.vector_cosine_ops);

CREATE TABLE rag_entity_embedding (
    knowledge_base_id UUID NOT NULL,
    object_id UUID NOT NULL,
    embedding public.vector(${ragVectorDimensions}) NOT NULL CHECK (public.vector_norm(embedding) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, object_id),
    FOREIGN KEY (knowledge_base_id, object_id) REFERENCES rag_entity(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_entity_embedding IS '实体独立向量集合';
COMMENT ON COLUMN rag_entity_embedding.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_entity_embedding.object_id IS '实体标识';
COMMENT ON COLUMN rag_entity_embedding.embedding IS '固定维度的有限非零向量';
COMMENT ON COLUMN rag_entity_embedding.updated_at IS '最近写入时间';

CREATE INDEX rag_entity_embedding_hnsw ON rag_entity_embedding USING hnsw (embedding public.vector_cosine_ops);

CREATE TABLE rag_relation_embedding (
    knowledge_base_id UUID NOT NULL,
    object_id UUID NOT NULL,
    embedding public.vector(${ragVectorDimensions}) NOT NULL CHECK (public.vector_norm(embedding) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, object_id),
    FOREIGN KEY (knowledge_base_id, object_id) REFERENCES rag_relation(knowledge_base_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_relation_embedding IS '关系独立向量集合';
COMMENT ON COLUMN rag_relation_embedding.knowledge_base_id IS '知识库标识';
COMMENT ON COLUMN rag_relation_embedding.object_id IS '关系标识';
COMMENT ON COLUMN rag_relation_embedding.embedding IS '固定维度的有限非零向量';
COMMENT ON COLUMN rag_relation_embedding.updated_at IS '最近写入时间';

CREATE INDEX rag_relation_embedding_hnsw ON rag_relation_embedding USING hnsw (embedding public.vector_cosine_ops);

