package com.trs.app.rag.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** 问答层调用智能体的业务契约，避免依赖智能体实现模块。 @author czhcc */
public interface AnswerAgent {
    /** 返回当前部署是否启用智能体回答。 @author czhcc */
    boolean enabled();

    /** 基于本轮知识库和完成历史回答，事件回调传递供应商真实增量。 @author czhcc */
    QueryResult answer(
            UUID kb,
            String actor,
            QueryRequest request,
            List<Map<String, String>> history,
            BiConsumer<String, Object> events);
}
