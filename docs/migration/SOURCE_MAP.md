# LightRAG 来源与差异台账

上游：[HKUDS/LightRAG 固定提交](https://github.com/HKUDS/LightRAG/tree/aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a)。所有下列路径均相对此提交；文件哈希见测试资源 provenance.json。生成器对调用函数直接导入执行；没有复制/改写上游算法主体。许可证见 third_party/LightRAG/LICENSE。

标记：**G** = 实际执行上游生成的 golden；**C** = 手写的目标验收契约，尚未执行。所有生产 Java 算法接入状态均为待实现；阶段 0 的测试作用域比较器不属于生产算法。

| 能力 | 上游文件 / 函数 | 样本或契约 ID | 预期与比较规则 | Java 目标 / 阶段 |
| --- | --- | --- | --- | --- |
| token 分块与位置 | chunker/token_size.py：chunking_by_token_size | G chunk-* | token 序列、完整块、顺序和码点区间严格一致；非法重叠抛错 | app.rag.chunk / 2 |
| 实体/关系解析 | operate.py：_handle_single_entity_extraction、_handle_single_relationship_extraction | G entity-*、relation-* | 字段、归一与拒绝记录一致；无权重字段的关系默认 1.0；自环拒绝 | app.rag.extraction / 3 |
| 抽取编排/gleaning | operate.py：extract_entities | G extraction-with-gleaning | 两次固定模型响应、提示词和输出实体/关系一致；时间固定 | app.rag.extraction / 3 |
| 描述去重 | operate.py：_combine_descriptions_dedup | G descriptions-* | 已存/新增边界计数、顺序及去重后的文本一致 | app.rag.graph / 3 |
| 来源合并 | utils.py：merge_source_ids | G sources-* | 展平、去空格、首次出现顺序一致，不丢完整来源 | app.rag.graph / 3 |
| 节点合并 | operate.py：_merge_nodes_then_upsert | G node-merge-reprocess | 每次写后快照和向量内容一致；同一贡献重放幂等 | app.rag.graph / 3 |
| 关系合并 | operate.py：_merge_edges_then_upsert | G edge-merge-reprocess | 来源、描述、权重及向量一致；c1/c1/c2 权重 1/1/2 | app.rag.graph / 3 |
| 摘要与模型调用边界 | operate.py：_handle_entity_relation_summary、_summarize_descriptions | G summary-* | 单描述/拼接/触发模型分支与提示词一致；固定响应不代表真实摘要质量 | app.rag.model / 3 |
| 关键词 | operate.py：_parse_keywords_payload | G keywords-* | 高低层关键词及顺序一致，列表中的短语不拆分；非法对象返回指定空结果 | app.rag.retrieval / 4 |
| local/global/hybrid/mix | operate.py：_perform_kg_search、_get_node_data、_get_edge_data | G search-local/global/hybrid/mix | 实体、边、排序和实际分支调用一致；浮点绝对误差 1e-8 | app.rag.retrieval / 4 |
| naive 与服务错误 | operate.py：_get_vector_context | G search-naive、search-backend-error | 精确向量顺序一致；后端异常必须传播 | app.rag.retrieval / 4 |
| 相关块配额 | operate.py：_vector_chunk_quota | G quota-* | 整数严格相等，正数输入下最小为 1；0 是关闭选择 | app.rag.retrieval / 4 |
| 三路块融合 | operate.py：_merge_all_chunks | G merge-chunks-three-sources | 向量/实体/关系三路轮询、块 ID 去重与最终顺序严格一致；此样本选择 WEIGHT 分支 | app.rag.context / 4 |
| token 预算 | utils.py：truncate_list_by_token_size | G budget-* | 完整项前缀、分隔符成本及空预算一致；受控 tokenizer | app.rag.context / 4 |
| 引用 | utils.py：generate_reference_list_from_chunks | G references-* | 按频率、首次出现编号；未知来源引用为空；块映射严格一致 | app.rag.context / 4 |
| 上下文和回答模板 | utils.py：render_chunks_context_text；prompt.py：rag_response | G context-bilingual | 渲染内容/引用一致；模板占位符和提示词文本完整保留 | app.rag.context / 4—5 |
| 重排适配 | utils.py：apply_rerank_if_enabled | G rerank-fixed-response | 固定 index/score 的输出顺序和对象字段一致，分数绝对误差 1e-8 | app.rag.context / 5 |
| 缓存摘要/隔离 | utils.py：compute_args_hash；operate.py：_answer_cache_kv | G cache-*；C cache-generation-isolation | 摘要参考值固定；本平台增加知识库/索引代隔离，不承诺物理缓存键兼容 | app.rag.model / 5 |
| 文档接收/任务恢复 | pipeline.py：apipeline_enqueue_documents、apipeline_process_enqueue_documents | C persistent-job-recovery | 输入/恢复检查点及无重复发布预期明确；新增 PostgreSQL 租约/CAS 机制 | app.rag.ingestion / 2 |
| 删除与剩余知识重建 | lightrag.py：adelete_by_doc_id；operate.py：rebuild_knowledge_from_chunks | C delete-shared-provenance、delete-shared-chunk | 仅删所属文档贡献，保留共享块/知识；删除后检索不可见 | app.rag.graph / 6 |
| 索引代发布与回滚 | pipeline.py：process_single_document，参考写入状态处理 | C index-generation-failure | 本平台新增版本发布契约：g2 失败时 g1 仍可读；不是上游现成原子事务承诺 | app.rag.ingestion / 6 |
| bypass | lightrag.py：aquery_llm | C bypass-no-retrieval | 模型原样回答，不访问图/向量且无引用；后置实现 | app.rag.query / 5 |
| 导航与认证边界 | 本项目 App.tsx；上游 api/routers 仅作功能参考 | Navigation.test.tsx | 只验证已有导航：未登录隐藏注销、恢复会话、双语和失败反馈；知识库菜单阶段 2 接入 | frontend/src/features / 2 |

## 已决定的适配差异

1. Python asyncio 改为 Java 服务与有界执行器；不能逐字翻译锁或使用无界线程池。
2. SQLite/文件/Neo4j/AGE 等不移植为必需依赖；参考 PGTableGraphStorage 的普通表图结构，统一 PostgreSQL 18 + pgvector，Flyway 管理结构。
3. 上游 ID 摘要与物理缓存键不作为 API 兼容契约；业务 ID 用 UUID，指纹、完整来源、知识库和索引代单独保存。适配器规范化 ID 前须登记映射，不能隐藏候选缺失。
4. 上游图端点采用规范化无向对；Java 按 Unicode 码点比较，不直接依赖 UTF-16 排序。存储同分排序在精确测试替身中固定为 ID；生产端另行验证。
5. 测试 tokenizer 为受控码点映射，保留名称和 token 序列。真实 BPE、模型维度、归一化及 ANN 是后续独立验收，不套用字符串长度作为生产 token 计数。
6. JPA 处理常规实体；向量/批量图访问用参数化原生仓库；模型调用不放入长事务，新增任务租约和索引代发布。
7. 上游摘要当前复用 extract 角色；目标可以独立配置摘要角色，这是适配增强，新增模型身份须进入缓存空间并单独测试。
8. 上游 HTTP/前端不直接复制；沿用本项目 Cookie/CSRF、REST/POST SSE 和中英文前端，字段适配须在接口契约记录。

## 尚待实现阶段补齐的测试

G 样本也不是完整分支覆盖。VECTOR 来源块选择、上下文四阶段整体预算、抽取 JSON 路径/超时/取消、真实摘要质量、缓存持久化、SSE、图编辑、解析格式及并发删除均需按主方案补测。C 样本不计入已通过算法案例或真实数据库结果。新增样本须先确定来源和预期，再增加实现。
