CREATE TABLE rag_model_configuration (
    id UUID PRIMARY KEY REFERENCES rag_model_profile(id) ON DELETE CASCADE,
    base_url VARCHAR(1000) NOT NULL,
    model VARCHAR(256) NOT NULL,
    secret_cipher TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 0 AND 2000),
    context_tokens INTEGER NOT NULL CHECK (context_tokens BETWEEN 1024 AND 1000000),
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens BETWEEN 64 AND 32000),
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 120),
    extra_body JSONB NOT NULL DEFAULT '{}'::JSONB
);
COMMENT ON TABLE rag_model_configuration IS '可直接编辑的当前模型参数，不保存配置版本';
COMMENT ON COLUMN rag_model_configuration.id IS '模型配置标识，与显示资料一致';
COMMENT ON COLUMN rag_model_configuration.base_url IS '兼容服务地址';
COMMENT ON COLUMN rag_model_configuration.model IS '供应商模型名称';
COMMENT ON COLUMN rag_model_configuration.secret_cipher IS '仅服务端使用的加密凭据';
COMMENT ON COLUMN rag_model_configuration.dimensions IS '向量维度，其他类型为零';
COMMENT ON COLUMN rag_model_configuration.context_tokens IS '模型上下文上限';
COMMENT ON COLUMN rag_model_configuration.max_output_tokens IS '最大输出 token';
COMMENT ON COLUMN rag_model_configuration.timeout_seconds IS '模型调用超时秒数';
COMMENT ON COLUMN rag_model_configuration.extra_body IS '文本模型扩展参数';
INSERT INTO rag_model_configuration
SELECT DISTINCT ON (profile_id) profile_id,base_url,model,secret_cipher,dimensions,context_tokens,max_output_tokens,timeout_seconds,extra_body
FROM rag_model_version ORDER BY profile_id,version DESC;

CREATE TABLE rag_knowledge_base_model_binding (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('extract','summary','keywords','answer','embedding','rerank')),
    model_id UUID NOT NULL REFERENCES rag_model_configuration(id),
    PRIMARY KEY (knowledge_base_id,role)
);
COMMENT ON TABLE rag_knowledge_base_model_binding IS '每个知识库独立的模型用途绑定';
COMMENT ON COLUMN rag_knowledge_base_model_binding.knowledge_base_id IS '绑定所属知识库';
COMMENT ON COLUMN rag_knowledge_base_model_binding.role IS '模型用途';
COMMENT ON COLUMN rag_knowledge_base_model_binding.model_id IS '当前可编辑的模型配置';
INSERT INTO rag_knowledge_base_model_binding
SELECT b.id,m.role,v.profile_id FROM rag_knowledge_base b CROSS JOIN rag_model_binding m
JOIN rag_model_version v ON v.id=m.version_id;
DROP TABLE rag_model_binding;

ALTER TABLE rag_model_version RENAME TO rag_index_model_snapshot;
DROP TRIGGER rag_model_version_immutable ON rag_index_model_snapshot;
ALTER TABLE rag_index_model_snapshot ADD COLUMN kind VARCHAR(16);
UPDATE rag_index_model_snapshot s SET kind=p.kind FROM rag_model_profile p WHERE p.id=s.profile_id;
ALTER TABLE rag_index_model_snapshot ALTER COLUMN kind SET NOT NULL;
ALTER TABLE rag_index_model_snapshot DROP CONSTRAINT rag_model_version_profile_id_fkey;
ALTER TABLE rag_index_model_snapshot DROP COLUMN version;
COMMENT ON TABLE rag_index_model_snapshot IS '索引生成时捕获的不可变执行参数，独立于可编辑和可删除的模型配置';
COMMENT ON COLUMN rag_index_model_snapshot.kind IS '快照模型类型';
COMMENT ON COLUMN rag_index_model_snapshot.profile_id IS '加密凭据关联标识，配置删除后仍保留';
CREATE TRIGGER rag_index_model_snapshot_immutable BEFORE UPDATE ON rag_index_model_snapshot
    FOR EACH ROW EXECUTE FUNCTION rag_reject_model_version_update();
DELETE FROM rag_index_model_snapshot s WHERE NOT EXISTS (
    SELECT 1 FROM rag_index_generation g WHERE s.id IN (g.extract_version,g.summary_version,g.embedding_version)
);
