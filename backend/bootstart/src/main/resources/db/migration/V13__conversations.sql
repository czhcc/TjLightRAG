CREATE TABLE rag_conversation (
    knowledge_base_id UUID NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL DEFAULT '',
    next_turn BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id,id)
);
COMMENT ON TABLE rag_conversation IS '用户在单个知识库中的私有对话';
COMMENT ON COLUMN rag_conversation.knowledge_base_id IS '知识库权限边界';
COMMENT ON COLUMN rag_conversation.id IS '对话标识';
COMMENT ON COLUMN rag_conversation.user_id IS '对话所属用户';
COMMENT ON COLUMN rag_conversation.title IS '首次提问生成的标题';
COMMENT ON COLUMN rag_conversation.next_turn IS '下一轮序号，在对话行锁内分配';
COMMENT ON COLUMN rag_conversation.created_at IS '创建时间';
COMMENT ON COLUMN rag_conversation.updated_at IS '最近提问时间';
CREATE INDEX rag_conversation_user_recent ON rag_conversation(knowledge_base_id,user_id,updated_at DESC,id);

CREATE TABLE rag_conversation_turn (
    knowledge_base_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    id UUID NOT NULL,
    turn_no BIGINT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'running' CHECK (status IN ('running','success','empty','failed','cancelled')),
    result JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id,conversation_id,id),
    UNIQUE (knowledge_base_id,conversation_id,turn_no),
    FOREIGN KEY (knowledge_base_id,conversation_id) REFERENCES rag_conversation(knowledge_base_id,id) ON DELETE CASCADE
);
COMMENT ON TABLE rag_conversation_turn IS '持久化问答轮次，只有完整成功的回答可进入后续上下文';
COMMENT ON COLUMN rag_conversation_turn.knowledge_base_id IS '知识库隔离边界';
COMMENT ON COLUMN rag_conversation_turn.conversation_id IS '所属对话';
COMMENT ON COLUMN rag_conversation_turn.id IS '请求标识，隔离迟到的完成及失败写入';
COMMENT ON COLUMN rag_conversation_turn.turn_no IS '对话内递增轮次';
COMMENT ON COLUMN rag_conversation_turn.question IS '用户原始问题';
COMMENT ON COLUMN rag_conversation_turn.answer IS '完整或明确标记未完成的回复';
COMMENT ON COLUMN rag_conversation_turn.status IS '运行、成功、无依据、失败或取消';
COMMENT ON COLUMN rag_conversation_turn.result IS '成功回答的索引代、来源及重排等信息';
COMMENT ON COLUMN rag_conversation_turn.expires_at IS '请求发布结果的截止时间，过期不得发布成功';
COMMENT ON COLUMN rag_conversation_turn.created_at IS '提问时间';
CREATE UNIQUE INDEX rag_conversation_one_running ON rag_conversation_turn(knowledge_base_id,conversation_id) WHERE status='running';
