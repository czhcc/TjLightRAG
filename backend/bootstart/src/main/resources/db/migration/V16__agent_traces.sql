CREATE TABLE rag_agent_trace (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    generation_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'running',
    incomplete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (knowledge_base_id,conversation_id,turn_id) REFERENCES rag_conversation_turn(knowledge_base_id,conversation_id,id) ON DELETE CASCADE,
    UNIQUE (knowledge_base_id,conversation_id,turn_id)
);
COMMENT ON TABLE rag_agent_trace IS '知识库私有对话每轮智能体调用跟踪';
COMMENT ON COLUMN rag_agent_trace.id IS '跟踪标识';
COMMENT ON COLUMN rag_agent_trace.knowledge_base_id IS '知识库隔离边界';
COMMENT ON COLUMN rag_agent_trace.conversation_id IS '所属私有对话';
COMMENT ON COLUMN rag_agent_trace.turn_id IS '所属回答请求';
COMMENT ON COLUMN rag_agent_trace.generation_id IS '本轮捕获的索引代，保留历史标识而不外键引用';
COMMENT ON COLUMN rag_agent_trace.status IS '运行、成功、失败、取消或中断';
COMMENT ON COLUMN rag_agent_trace.incomplete IS '跟踪写入或正文捕获不完整';
COMMENT ON COLUMN rag_agent_trace.created_at IS '开始时间';
COMMENT ON COLUMN rag_agent_trace.updated_at IS '最后更新时间';
CREATE TABLE rag_agent_trace_span (
    id UUID PRIMARY KEY,
    trace_id UUID NOT NULL REFERENCES rag_agent_trace(id) ON DELETE CASCADE,
    parent_id UUID,
    sequence INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    label VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'running',
    offset_ms BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 1,
    summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    UNIQUE (trace_id,sequence)
);
COMMENT ON TABLE rag_agent_trace_span IS '智能体父子调用步骤及无正文摘要';
COMMENT ON COLUMN rag_agent_trace_span.id IS '步骤标识';
COMMENT ON COLUMN rag_agent_trace_span.trace_id IS '所属跟踪';
COMMENT ON COLUMN rag_agent_trace_span.parent_id IS '父步骤标识';
COMMENT ON COLUMN rag_agent_trace_span.sequence IS '本轮递增步骤序号';
COMMENT ON COLUMN rag_agent_trace_span.name IS '英文工具或模型操作名称';
COMMENT ON COLUMN rag_agent_trace_span.label IS '中文显示名称';
COMMENT ON COLUMN rag_agent_trace_span.status IS '步骤状态';
COMMENT ON COLUMN rag_agent_trace_span.offset_ms IS '相对本轮开始时间毫秒';
COMMENT ON COLUMN rag_agent_trace_span.duration_ms IS '调用耗时毫秒';
COMMENT ON COLUMN rag_agent_trace_span.revision IS '运行到完成的摘要修订号';
COMMENT ON COLUMN rag_agent_trace_span.summary IS '安全的用量、完成原因和计时信息';
CREATE TABLE rag_agent_trace_payload (
    id UUID PRIMARY KEY,
    span_id UUID NOT NULL REFERENCES rag_agent_trace_span(id) ON DELETE CASCADE,
    side VARCHAR(8) NOT NULL CHECK (side IN ('request','response')),
    part INTEGER NOT NULL,
    cipher TEXT NOT NULL,
    key_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '7 days',
    UNIQUE (span_id,side,part)
);
COMMENT ON TABLE rag_agent_trace_payload IS '脱敏后按段加密的模型和工具请求及返回，不进入普通日志';
COMMENT ON COLUMN rag_agent_trace_payload.id IS '绑定加密认证数据的分段标识';
COMMENT ON COLUMN rag_agent_trace_payload.span_id IS '所属步骤';
COMMENT ON COLUMN rag_agent_trace_payload.side IS '请求或响应';
COMMENT ON COLUMN rag_agent_trace_payload.part IS '从零开始的分段序号';
COMMENT ON COLUMN rag_agent_trace_payload.cipher IS '独立用途密钥加密的 AES-GCM 内容';
COMMENT ON COLUMN rag_agent_trace_payload.key_version IS '密钥派生用途版本';
COMMENT ON COLUMN rag_agent_trace_payload.expires_at IS '正文保留截止时间';
CREATE INDEX rag_agent_trace_payload_expiry ON rag_agent_trace_payload(expires_at);
