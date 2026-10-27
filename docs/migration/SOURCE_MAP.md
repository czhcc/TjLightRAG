# LightRAG 来源与差异台账

上游：[HKUDS/LightRAG 固定提交](https://github.com/HKUDS/LightRAG/tree/aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a)。所有下列路径均相对此提交；文件哈希见测试资源 provenance.json。生成器对调用函数直接导入执行；没有复制/改写上游算法主体。许可证见 third_party/LightRAG/LICENSE。

标记：**G** = 实际执行上游生成的 golden；**C** = 手写的目标验收契约，尚未执行。阶段 2 已接入真实 BPE 固定窗口及文档/任务管线；阶段 3 已接入抽取、合并及模型适配；阶段 4 已接入五模式查询与引用；阶段 5 已接入回答、流式、重排和禁用缓存策略（真实模型验收待完成）；阶段 6 高级管理仍待实现。阶段 0 的测试作用域比较器不属于生产算法。

| 能力 | 上游文件 / 函数 | 样本或契约 ID | 预期与比较规则 | Java 目标 / 阶段 |
| --- | --- | --- | --- | --- |
| token 分块与位置 | chunker/token_size.py：chunking_by_token_size | G chunk-*；独立 bpe-corpus.json 9 案例 | 真实 BPE 的 legacy 固定窗口/分隔符对照已通过；生产 UTF-8 安全边界差异显式记录 | app.rag.ingestion.FixedTokenChunker / 2 已接入 |
| 实体/关系解析 | operate.py：_handle_single_entity_extraction、_handle_single_relationship_extraction | G entity-*、relation-* | 字段、归一与拒绝记录一致；无权重字段的关系默认 1.0；自环拒绝 | app.rag.index.ExtractionParser、IndexProcessor / 3 已接入 |
| 抽取编排/gleaning | operate.py：extract_entities | G extraction-with-gleaning | 两次固定模型响应、提示词和输出实体/关系一致；时间固定 | app.rag.index.ExtractionParser、IndexProcessor / 3 已接入 |
| 描述去重 | operate.py：_combine_descriptions_dedup | G descriptions-* | 已存/新增边界计数、顺序及去重后的文本一致 | app.rag.index.GraphAssembler / 3 已接入 |
| 来源合并 | utils.py：merge_source_ids | G sources-* | 展平、去空格、首次出现顺序一致，不丢完整来源 | app.rag.index.GraphAssembler / 3 已接入 |
| 节点合并 | operate.py：_merge_nodes_then_upsert | G node-merge-reprocess | 每次写后快照和向量内容一致；同一贡献重放幂等 | app.rag.index.GraphAssembler / 3 已接入 |
| 关系合并 | operate.py：_merge_edges_then_upsert | G edge-merge-reprocess | 来源、描述、权重及向量一致；c1/c1/c2 权重 1/1/2 | app.rag.index.GraphAssembler / 3 已接入 |
| 摘要与模型调用边界 | operate.py：_handle_entity_relation_summary、_summarize_descriptions | G summary-* | 单描述/拼接/触发模型分支与提示词一致；固定响应不代表真实摘要质量 | app.rag.index.GraphAssembler、app.rag.model.ModelGateway / 3 已接入 |
| 关键词 | operate.py：_parse_keywords_payload | G keywords-* | 高低层关键词及顺序一致，列表中的短语不拆分；非法对象返回指定空结果 | app.rag.retrieval / 4 已接入 |
| local/global/hybrid/mix | operate.py：_perform_kg_search、_get_node_data、_get_edge_data | G search-local/global/hybrid/mix | 实体、边、排序和实际分支调用一致；纯算法误差 1e-8；PostgreSQL FLOAT32 对照误差 1e-6 | app.rag.retrieval / 4 已接入 |
| naive 与服务错误 | operate.py：_get_vector_context | G search-naive、search-backend-error | 精确向量顺序一致；后端异常必须传播 | app.rag.retrieval / 4 已接入 |
| 相关块配额 | operate.py：_vector_chunk_quota | G quota-* | 整数严格相等，正数输入下最小为 1；0 是关闭选择 | app.rag.retrieval / 4 已接入 |
| 三路块融合 | operate.py：_merge_all_chunks | G merge-chunks-three-sources | 向量/实体/关系三路轮询、块 ID 去重与最终顺序严格一致；此样本选择 WEIGHT 分支 | app.rag.retrieval.ContextPlanner / 4 已接入 |
| token 预算 | utils.py：truncate_list_by_token_size | G budget-* | 完整项前缀、分隔符成本及空预算一致；受控 tokenizer | app.rag.retrieval.ContextPlanner / 4 已接入 |
| 引用 | utils.py：generate_reference_list_from_chunks | G references-* | 按频率、首次出现编号；未知来源引用为空；块映射严格一致 | app.rag.retrieval.ContextPlanner / 4 已接入 |
| 上下文和回答模板 | utils.py：render_chunks_context_text；prompt.py：rag_response | G context-bilingual | 渲染内容/引用一致；模板占位符和提示词文本完整保留 | app.rag.retrieval / 4 已接入模板和预算；5 已接入生成和预览 |
| 重排适配 | utils.py：apply_rerank_if_enabled | G rerank-fixed-response | 固定 index/score 的输出顺序和对象字段一致，分数绝对误差 1e-8 | app.rag.query.QueryService / 5 已接入固定输出对照 |
| 缓存摘要/隔离 | utils.py：compute_args_hash；operate.py：_answer_cache_kv | G cache-*；C cache-generation-isolation | 摘要仅为固定基线；生产首期完全禁用回答缓存，未来启用须单独实现完整隔离 | app.rag.query / 5 首期完全禁用回答缓存 |
| 文档接收/任务恢复 | pipeline.py：apipeline_enqueue_documents、apipeline_process_enqueue_documents | C persistent-job-recovery；IngestionIntegrationTest 与真实 JVM 重启实验 | 阶段 2 验证恢复意图；解析检查点 + 原子块提交代替草案 chunks_saved 部分写入状态，不宣称逐字执行该手写场景 | app.rag.ingestion / 2 已接入 |
| 删除与剩余知识重建 | lightrag.py：adelete_by_doc_id；operate.py：rebuild_knowledge_from_chunks | C delete-shared-provenance、delete-shared-chunk | 仅删所属文档贡献，保留共享块/知识；删除后检索不可见 | app.rag.graph / 6 |
| 索引代发布与回滚 | pipeline.py：process_single_document，参考写入状态处理 | C index-generation-failure | 本平台新增版本发布契约：g2 失败时 g1 仍可读；不是上游现成原子事务承诺 | app.rag.ingestion / 6 |
| bypass | lightrag.py：aquery_llm | C bypass-no-retrieval | 模型原样回答，不访问图/向量且无引用；后置实现 | 后置待实现，不属于阶段 5 已交付 |
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

G 样本也不是完整分支覆盖。阶段 4 已验证 VECTOR 来源块选择和整体预算，阶段 5 已验证本地 SSE/取消及禁用缓存；抽取 JSON 路径、真实模型/摘要质量、持久化缓存设计、图编辑、扩展解析格式及并发删除仍需按主方案专项验收。C 样本不计入已通过算法案例或真实数据库结果。新增样本须先确定来源和预期，再增加实现。

## 阶段 2 已落地差异

真实 BPE 词表获取限制已解决，原受控 fixture 保留。新增 cl100k_base 对照独立版本化；生产分块调整 UTF-8 边界，明确不承诺与会产生替换字符的原始窗口逐块相同。仅支持严格 UTF-8 TXT/Markdown，保留正文、BOM 处理和来源区间域说明。任务恢复采用 PostgreSQL 租约/fencing token、解析检查点和原子提交，不逐字翻译 asyncio/JVM 内存锁。版本与原文校验失败不静默回退；详情和验收见 STAGE_2.md。

## 阶段 3 已落地差异

固定 entity/relation 的 8 个案例、gleaning 固定响应及三份渲染提示词、节点/边最终合并结果已进入生产候选对照测试。时间、对象 ID、来源 ID 使用本平台数据库标识；不是把测试作用域比较器当作生产算法。

不以增量累加旧图实现幂等，而按每块唯一、不可变 contribution 重建本代图；c1/c1/c2 的最终来源/描述/权重与上游一致，重放检查点不再次计数。类型同票改为字典序，来源顺序按本代稳定块顺序，而非依赖异步先到者。来源全集保留在贡献与修订关联中。

生产要求正常完成信号，截断/缺失 COMPLETE 明确失败；没有照搬全部格式猜测恢复或 JSON 抽取。摘要使用原模板，但使用本平台 10 项/1000 token 阈值和有界分层预算，故不宣称任意摘要配置逐字一致。向量由 Spring AI 模型接口的兼容协议适配器生成；固定协议响应只用于测试。密钥管理、索引代/维度隔离与原子切换属于本平台新增能力；详情见 STAGE_3.md。

## 阶段 4 已落地差异

五模式召回、双层关键词、图扩展/排序、配额、WEIGHT 分配、三路合并、完整项前缀预算和引用均由生产候选对照冻结样本。实际 SQL 只读取捕获的知识库/索引代，批量向量使用该代 embedding_version，关键词模型单独捕获。模板保持固定上游来源；数据占位符用单次替换，不能被资料中的花括号再次解释。

缺失 local/global 所需关键词不借用相反分支，mix 无图关键词仍执行块召回；非法关键词 JSON 和存储异常不伪装成空结果。未引入 JSON 修复猜测、查询缓存或重排。新平台共享块保留所有修订来源，引用在预算裁剪后重算，原文按 Unicode 码点回看。

来源标题作为有界数组进入实际序列化和预算，不声称与所有上游存储的 breadcrumb 字符串逐字相同；无标题冻结上下文与回答模板逐字对照。完整提示词预算增加 1024 输出预留和 16 消息开销，并对图本身超总预算的情况裁剪，不只依赖上游固定 200 token 余量。

受控 HNSW 反例证实同维度跨索引代过滤可能不足 topK，因此生产保持精确 cosine；ANN 未默认启用。所有限额、同分规则、浮点精度与实机/模型模拟测试范围见 STAGE_4.md。

## 阶段 5 补充差异

QueryService 复用固定 rag_response，将重排放在最终 chunk_top_k/预算/引用之前。apply_rerank_if_enabled 的固定样本实际对照生产 Java，包括独立 rerank_score；最小分数 0.5，供应商错误与非法输出直接失败，区别于上游部分静默回退。naive 沿用阶段 4 统一图/块上下文和回答模板，未单独复制 naive_rag_response；这是已有的统一模板适配，不声称与上游两套提示词字节等同。

首期单轮、禁止任意系统提示/历史字段，完全不读写回答缓存；缓存摘要基线仍保存，但不声称生产启用了这些缓存算法。对返回引用只验证编号是否对应保留依据，事实支持程度仍需真实模型评估。POST SSE metadata/references/delta/error/done 与会话作用域取消是本工程协议，不逐字兼容上游 API。详细资源上限、差异与验收见 STAGE_5.md。
