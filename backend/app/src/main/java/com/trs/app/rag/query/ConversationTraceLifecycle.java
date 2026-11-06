package com.trs.app.rag.query;

import java.util.UUID;

/** 对话持久化完成后校正调用跟踪状态的契约，实现由智能体模块提供。 @author czhcc */
public interface ConversationTraceLifecycle {
    /** 按本人的对话轮次同步最终状态，跟踪失败不得替代原问答结果。 @author czhcc */
    void terminal(UUID kb, UUID conversation, UUID turn, String actor, String status);
}
