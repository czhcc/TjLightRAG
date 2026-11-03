ALTER TABLE rag_index_entity DROP CONSTRAINT rag_index_entity_knowledge_base_id_generation_id_name_key;
ALTER TABLE rag_index_entity ADD CONSTRAINT rag_index_entity_name_type_unique
    UNIQUE (knowledge_base_id,generation_id,name,entity_type);
COMMENT ON COLUMN rag_index_entity.id IS '索引代内节点标识，新协议由规范化名称与类型共同生成，历史标识保留';
COMMENT ON COLUMN rag_index_entity.entity_type IS '规范化实体类型，优先五要素且允许其他有原文依据的类型';
COMMENT ON COLUMN rag_index_entity.attributes IS '按属性名归组的类型与原生 JSON 值列表，兼容历史字符串列表，来源关联保留于实体 source_ids';
