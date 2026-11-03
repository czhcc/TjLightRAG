ALTER TABLE rag_model_version ADD COLUMN extra_body JSONB NOT NULL DEFAULT '{}'::JSONB
    CHECK (JSONB_TYPEOF(extra_body) = 'object');
COMMENT ON COLUMN rag_model_version.extra_body IS '文本模型请求扩展参数，不可覆盖受控参数或包含凭据，随不可变版本保存';
