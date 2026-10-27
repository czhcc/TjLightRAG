# 阶段 4：五种检索模式、上下文预算与引用

状态：工程交付及本地验收完成；真实供应商模型和生产语料质量仍需联调。

## 交付边界

使用固定 LightRAG 提交 `aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a`，移植检索数据管线，不以模型回答代替算法验证。实现 naive、local、global、hybrid、mix 五种模式，默认 mix；返回候选、实体、关系、文本块、引用、上下文及诊断信息。回答、SSE、查询重排与回答缓存属于阶段 5，响应明确 `reranked=false`。

## 模型、索引与权限

每次请求通过一个 SQL 语句捕获当前 ready 的 `active_generation_id`、该代 `embedding_version`、维度、tokenizer，以及本次关键词模型版本（优先 keywords 绑定，未绑定时使用该代 extract_version）。之后模型调用与所有图谱/向量读取固定这些版本；全局绑定或当前索引在请求中途切换，不改变本次查询。新请求才读取新指针。

模型调用不持有数据库事务；各 SQL 读取为最长 15 秒的短事务，重新检查知识库成员及捕获代的 ready 状态，返回前再次校验权限。READER 可检索和读取历史来源；未授权对象返回 404。无激活索引返回 409，不能用未发布数据代替。API 密钥和密文不回传；不记录查询或模型正文。校验异常由脱敏 handler 处理，避免默认日志回显 rejected value。

naive 不调用关键词模型，只生成原问题向量。图模式仅按所需用途组成一次批量 embedding 请求：query（块召回或 VECTOR 来源选择）、low（实体）、high（关系）；相同文本只嵌入一次。查询向量不使用全局新 embedding 绑定解释旧索引。关键词提示词、上下文和回答模板从上游 prompt.py 提取，保存在 `backend/app/src/main/resources/rag/retrieval`，独立 PROVENANCE.json 记录源文件哈希和版本。

## 算法顺序

1. 关键词：读取 `high_level_keywords` / `low_level_keywords`，支持严格 JSON 对象或 fenced JSON，移除 think 包裹。字符串按换行/逗号/分号拆分；数组保留每项内的多词短语和逗号，非字符串项忽略。每类最多 32 项，每项最多 256 字符；无效 JSON 是失败，不冒充空召回。首期不引入 json_repair 猜测修复或关键词缓存。
2. 手动提供任一关键词列表时跳过关键词模型，另一缺失列表视为空；手动空列表显式禁用对应图分支。自动关键词全空时，少于 50 Unicode 码点的问题使用原问题作为低层关键词；长问题不自动塞入实体关键词。local/global 不切换到相反分支，mix 即使图关键词为空仍保留块向量召回；这两项明确修正了上游条件分支可能的模式借用/提前结束行为。
3. naive：按块向量 cosine 排序，阈值后取 chunkTopK。
4. local：按低层关键词实体相似度取 topK；实体保留向量顺序并附节点度数；加载相邻无向边，按两端度数之和、关系权重降序排列。
5. global：按高层关键词关系相似度取 topK；关系保留向量顺序，不按权重改排；按端点首次出现顺序返回实体。
6. hybrid：分别执行 local/global，实体按名字、无向边按规范端点轮询去重，先 local 后 global。mix 再执行独立的原问题块向量召回。
7. 图预算：投影实际发送的实体/关系 JSON（不把诊断来源 ID、时间等计入模型内容），按序保留完整前缀。用完整提示词加问题与预留再次检查；若图已超总预算，先移除尾部关系，再移除尾部实体，再选择来源块。
8. 来源选择：从保留下来的图项收集完整贡献块，计算出现次数；相同块归最早的图项，空组移除，组内按出现次数降序。实体先选；关系排除已选实体块，再重复分组。VECTOR 为默认，按原问题向量只在候选集合中排序，配额为正输入 `max(1, relatedChunkNumber*groupCount/2)`；WEIGHT 使用上游线性梯度、Python half-even 舍入和剩余配额再分配。relatedChunkNumber=0 关闭图来源选择，不关闭 mix 的独立块向量分支。缺失候选向量是索引完整性错误，不静默降级为 WEIGHT。
9. 三路块按 vector、entity、relation 轮询并按 chunk ID 去重，保留多路径标记；再从捕获的文档修订快照读取来源，不能读取文档当前修订来解释旧向量。
10. 引用在最终裁剪后重算，按文件在保留块中的出现频率降序、首次出现顺序分配编号。共享块只返回一次正文，保留所有文档/修订/出现位置和 reference_ids。实际文档以数据库身份和快照为准；`unknown_source` 只有无真实来源的基准样本才视为未知。
11. 上下文只裁剪完整记录/块，不切割正文或伪造来源。先近似块前缀，再用最终引用、标题、引用列表、图谱及完整上游回答提示词复核；cl100k_base 统计总量包括原问题、16 token 消息开销及 1024 token 输出预留，确保 totalTokens ≤ maxTotalTokens。该预留用于未来回答，不表示本阶段已调用回答模型。所有字符串替换单次执行，文档中的 `{placeholder}` 不再被当成模板变量。

向量召回同分按数据库对象 ID 排序；相邻边度数/权重同分按规范端点名排序，避免依赖异步和物理行顺序。来源向量同分保留候选首次出现顺序。纯算法对照保持原精度；PostgreSQL vector 存储为 FLOAT32，cosine 分数与双精度参考采用 1e-6 绝对误差（数据库阈值仍严格作用于实际存储分数，不悄悄放宽）。以上为明确的确定性适配，不宣称与任意上游存储的未定义同分顺序相同。

## REST 与前端

`POST /api/knowledge-bases/{kb}/retrieval`：会话认证、CSRF；请求为 JSON：

| 参数 | 默认 / 限制 |
| --- | --- |
| query | 必填，最多 8000 UTF-16 字符且实际不超过 2048 cl100k_base token |
| mode | mix；仅 naive/local/global/hybrid/mix，拒绝 bypass |
| topK、chunkTopK | 各 20，1..100 |
| relatedChunkNumber | 5，0..20 |
| chunkSelection | VECTOR；可选 WEIGHT |
| cosineThreshold | 0.2，有限数值 -1..1，SQL 使用距离 ≤ 1-threshold |
| maxEntityTokens、maxRelationTokens | 6000 / 8000，0..16000 |
| maxTotalTokens | 16000，1024..64000；不足完整提示词和预留时返回 400 |
| highLevelKeywords、lowLevelKeywords | 可选手动列表，最多 32 项，每项 256 字符 |

响应包含 status=success/empty、捕获 generationId / embeddingVersion / keywordVersion、关键词、实体/关系、文本块、所有来源位置、references、context，以及分支、计数、耗时和精确预算。`empty` 只用于正常完成但无证据；模型错误为 502、数据库故障为 503、请求期限为 504、繁忙为 429，均不转换为成功的空结果。

`GET /api/knowledge-bases/{kb}/retrieval/source`：参数 generationId、chunkId、documentId、revisionId、chunkOrder 必须完整匹配该代快照。返回 Unicode 码点来源区间及前后各约 160 码点的原文片段；再次验证片段选区与存储块完全一致。前端按 Unicode 码点高亮，正确处理中文和 emoji，不执行 Markdown HTML。

前端知识库页面新增“检索证据”：五种模式、问题、召回数量、高级阈值/预算/来源策略/手动关键词、块与引用、历史来源高亮、实体关系和上下文诊断。切换知识库清除草稿与结果并中止旧读取；“停止等待”中止浏览器等待，不声称强杀已经发出的服务端模型请求，服务端仍有调用期限。

## 限额与部署

- JSON 请求体上限 256 KiB，在 Jackson 分配前检查，即使未给 Content-Length 也有效；文档粘贴仍为 6 MiB。
- `RAG_RETRIEVAL_MAX_CONCURRENCY` 默认 4，范围 1..16；实例满额立即 429，不在请求线程无限排队。多个实例各自限额，未声称实现供应商全局限流。
- `RAG_RETRIEVAL_TIMEOUT_SECONDS` 默认 120，范围 1..180；模型调用按剩余时间收紧原配置期限，完成前复查截止时间；SQL 短事务单独限 15 秒，连接获取仍受 Hikari 配置控制。
- 每次最多展开 2000 条邻边、检查 50000 个图贡献 ID、合并 2200 个候选块、解析 5000 个来源位置，超限明确 413，不静默丢失完整来源。标题展示最多 8 层、每层 256 码点；完整正文及来源仍保存在修订中。
- V6 只增加索引代内关系末端点和修订查找 B-tree 索引；V1—V5 不改写。查询仅使用 `rag_index_*`，不混入旧 V2 原始向量表。

## 精确检索与 ANN 评估

生产默认按知识库/索引代的主键范围精确 cosine 扫描，返回 `vectorStrategy=exact`。在独立真实 PostgreSQL 18 + pgvector 0.8.7 中，构造两个索引代、同为 3 维但相互隔离的候选，创建测试用表达式 HNSW 并验证 EXPLAIN 确实走该索引；设置 ef_search=10、iterative_scan=off。实际结果：目标代精确检索 10 条，全局 ANN 后过滤只返回 1 条，Recall@10=0.1。

这是受控的过滤不足反例，不是生产模型质量指标或 ANN 普遍性能结论。它证明“按维度建一个全局 HNSW，然后追加知识库/索引代过滤”不能直接当作正确生产召回。因此本阶段保留精确基线；待真实模型空间和规模确定后，按空间/代分区或部分索引，评估 iterative scan、过滤不足精确回退、召回率及延迟，再显式启用 ANN。测试临时索引已删除，没有把测试 DDL 留在部署迁移中。

## 验证命令与结果

```sh
source docs/scripts/cloud-env.sh
cd backend
mvn -B verify
cd ../frontend
pnpm test
pnpm build
```

算法测试调用生产实现，对照固定上游的五模式候选、关键词、配额、三路来源合并、引用和逐字上下文；标量 tokenizer 仅重放原受控预算样本，另有真实 cl100k_base 的完整提示词预算检查。数据库测试用独立 Testcontainers 和本地协议服务；它们证明接口与算法，不代表真实供应商回答质量。

完整后端 `mvn -B verify`：131 项通过，0 失败、0 错误、0 跳过。补充“邻边度数优先于更高权重”的独立冲突用例后，检索相关 21 项再次通过（含 13 项真实 PostgreSQL 集成和 8 项算法测试）；当前共 132 项 JUnit 结果通过，0 失败、0 错误、0 跳过。数据库连接初始化失败也验证为脱敏 503，并释放并发槽。前端 Vitest + jsdom：20 项通过，TypeScript/Vite 生产构建通过。既有 V1—V5 和冻结基线文件未改写。

实际开发服务完成 V5→V6 升级；独立空库上的 prod profile 完整单镜像完成 V1—V6。两组均经真实 HTTP 完成上传两份共享正文、后台分块/抽取/索引、五模式检索、每查询一次批量 embedding、无图关键词空结果、Unicode 原文选区、当前文档替换后仍读取捕获修订，以及全局向量模型改绑/3 维→4 维激活/回退后的正确模型选择。模拟服务返回 503 时 API 返回 502，而不是成功的空结果；无回答或重排调用。

Python tiktoken 0.12.0 独立按同一固定模板、实际返回上下文和问题复核完整预算，两组均为 2052/2200 token，与 Java 报告完全一致。它验证真实 BPE 与渲染预算，不把模型模拟响应当作真实供应商语义召回质量。

镜像使用已通过回归的 Maven jar、匹配的当前前端产物和现有 Dockerfile 的最终运行层组装；镜像 `sha256:8875814920ff096d3c8880bbb1e45305ad35a90ffb41f90298222b3cc9bf3c79`，运行 UID 10001，受限数据库账号完成检索与来源读取，JS/CSS 与本次构建一致，第三方许可随镜像可读。忽略文件 `docker/images/tjlightrag.tar` 已更新，旧阶段 3 镜像归档另行保留。

测试配置和知识库已删除，原用途绑定已恢复；未删除既有命名数据库卷。升级前 V5 数据库备份和对应模型主密钥保存在 Git 外 `/workspace/backups/tjlightrag-before-stage4.dump` 及配套受限文件，不将密钥或备份提交。变更尚未提交或推送。

下一阶段为回答、SSE、可选重排与问答界面。
