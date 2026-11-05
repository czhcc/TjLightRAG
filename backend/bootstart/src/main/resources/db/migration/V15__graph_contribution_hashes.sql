ALTER TABLE rag_index_entity ADD COLUMN contribution_hash VARCHAR(64);
COMMENT ON COLUMN rag_index_entity.contribution_hash IS '原始合并贡献及来源块的 SHA-256；旧记录为空时重新计算';

ALTER TABLE rag_index_relation ADD COLUMN contribution_hash VARCHAR(64);
COMMENT ON COLUMN rag_index_relation.contribution_hash IS '关系原始合并贡献、类型及来源块的 SHA-256；旧记录为空时重新计算';
