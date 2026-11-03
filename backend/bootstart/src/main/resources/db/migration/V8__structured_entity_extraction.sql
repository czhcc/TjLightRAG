ALTER TABLE rag_index_entity ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::JSONB
    CHECK (JSONB_TYPEOF(attributes) = 'object');
COMMENT ON COLUMN rag_index_entity.attributes IS '实体自定义属性及按来源去重的值列表，历史索引默认为空';

ALTER TABLE rag_index_relation ADD COLUMN relation_types JSONB NOT NULL DEFAULT '[]'::JSONB
    CHECK (JSONB_TYPEOF(relation_types) = 'array');
COMMENT ON COLUMN rag_index_relation.relation_types IS '模型从原文提取并归并的关系类型列表，历史索引默认为空';
