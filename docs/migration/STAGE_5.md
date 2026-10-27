# 阶段 5：回答、流式输出与问答界面

状态：工程功能已实现；本地协议、数据库与界面验收结果见下文。尚未选择并配置真实供应商，不能据此宣称真实模型质量或计划中的真实供应商退出条件已经通过。

## 上游依据与移植范围

固定基线仍为 `aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a`。本阶段实际读取 `operate.py:kg_query/naive_query/_get_vector_context/_build_context_str`、`utils.py:apply_rerank_if_enabled/normalize_rerank_result/process_chunks_unified` 和现有 `rag_response`。Java 运行时不依赖 Python 服务。

- 回答与预览共享阶段 4 捕获的索引代、三类向量、图谱、来源和完整模板。生成开始时单次捕获回答/重排配置版本；处理中切换全局绑定或激活新代，不改变本次回答的模型与证据。下一请求读取新配置。
- 回答通过 Spring AI `ChatModel`，真实增量输出通过 `StreamingChatModel`，由既有受限 OpenAI 兼容协议适配器提供。供应商必须提供 `/chat/completions`；流式路径要求 SSE，不把完整回答拆分伪装为模型流。
- 保留上游回答模板，默认 Multiple Paragraphs。只在上下文不为空时调用回答模型；无证据返回固定中/英文拒答，`status=empty`、`llmGenerated=false`。模型/存储失败不能变成无证据成功。
- 可选重排插入“融合去重、读取完整来源”之后、“chunkTopK、最终预算截断、引用重算”之前；按供应商索引顺序重排，默认分数阈值 0.5。`rerank_score` 与 cosine `score` 分开，保留全部来源，不修改原候选对象。固定上游 `rerank-fixed-response` 的顺序、内容、文件名和分数逐项对照。
- `enableRerank` 默认 false。请求启用但没有重排绑定时返回 `not_configured` 并保持原顺序；配置可用但无候选返回 `no_candidates`；执行成功返回 `applied`。非法、重复、非整数或越界索引、非有限分数、空返回、超时和 HTTP 错误均明确失败。这与上游部分异常回退行为有意不同，避免无提示地改变结果。
- 所有回答缓存读写均禁用，响应明确 `cacheStatus=disabled`，不使用 V2 `rag_llm_cache`。也不缓存本阶段关键词或重排输出。因此不存在跨用户、知识库、索引代或模型版本复用回答的路径。后续开启缓存必须单独设计完整隔离键及失效规则。
- 首期仅单轮；不接收历史、自定义系统指令或客户端缓存开关。未知查询字段返回 400，不静默忽略。`bypass` 不开放。上下文/提示词预览不调用回答模型，但可能调用关键词、向量及启用的重排模型。

## 接口

写接口均需会话与 CSRF，回答/预览响应 `Cache-Control: no-store`。

| 接口 | 行为 |
| --- | --- |
| `POST /api/knowledge-bases/{kb}/query` | JSON 回答；`preview=context` 仅上下文，`preview=prompt` 返回实际 system/user 消息 |
| `POST /api/knowledge-bases/{kb}/query/stream` | `fetch` POST SSE；必须提供随机 UUID `requestId`，支持三种 preview 值 |
| `POST /api/knowledge-bases/{kb}/query/{requestId}/cancel` | 仅取消同知识库、用户名、HTTP 会话下的请求；未知 ID 幂等 204 |
| `GET /api/knowledge-bases/{kb}/retrieval/source` | 沿用阶段 4 完整来源元组和历史修订权限校验 |

请求继承检索全部限制，新增 `preview=answer|context|prompt`（默认 answer）、`enableRerank`（默认 false）、`minRerankScore`（默认 0.5，范围 0..1）。不提供自动重试或自动重连，避免重复计费。非流式结果返回 answer、status、llmGenerated、answerVersion、rerankVersion、rerankStatus、cacheStatus、完整 retrieval 和引用校验。实际消息只在 prompt 预览返回。

SSE 每帧为 `event: name` 和 JSON `data`，空行结束：

| 事件 | 内容 / 顺序 |
| --- | --- |
| metadata | requestId、generationId、answerVersion、模式、重排/缓存状态及总预算 |
| references | 实际检索结果，包括保留块、全部来源、引用和诊断 |
| delta | `{ "text": "…" }`；供应商产生增量后立即转发 |
| heartbeat | 每 2 秒检测连接和知识库权限，客户端忽略正文 |
| error | 脱敏错误码；终止，不再发送 done |
| done | QueryResult，status 为 success/empty/preview；仅全部成功时发送 |

中途断流即使已输出部分内容也不算成功。浏览器停止时同时 AbortController 中止读取、发显式取消请求；关闭页面/失联由心跳写失败检测，取消模型订阅并关闭供应商 HTTP 输入流。取消发生在注册前时，浏览器中止仍由连接检测和截止时间兜底。取消不承诺供应商已停止计费。

取消注册表为实例内存状态：多实例部署须对会话和流式/取消请求保持同实例路由；目前没有跨实例取消总线。服务重启会中断正在执行的回答，前端显示未完成，不持久化或自动恢复生成。

## 预算、资源与权限

- 保留阶段 4 完整记录预算；额外扣除回答模型实际配置输出超过 1024 的差额和 16 token 消息开销差额，使真实 `system + user + 32 消息开销 + maxOutputTokens` 同时不超过请求总额与模型上下文上限。`retrieval.metadata.answerBudget` 返回这一实际保护统计，阶段 4 的 `budget` 继续描述上下文计划。都是 cl100k_base 估计和配置预留，不冒充供应商账单用量。
- 预览与生成采取相同模型上限及重排设置；禁止将预览可见但实际不会发送的超预算块作为依据。
- 输出引用 `[数字]` 对照实际 references，返回 `citedReferenceIds` 与 `unknownReferenceIds`；未知编号不解析为原文，界面提醒。未改写供应商回答，也不声称编号匹配等同于事实已被证据支持。首版答案为纯文本，原文按码点高亮，不执行模型 HTML/Markdown。
- 每次模型调用不占长数据库事务。生成前、每段增量和完成前复核知识库权限及原捕获索引代状态；心跳也复核权限。
- `RAG_QUERY_MAX_CONCURRENCY=4`，范围 1..16，与同步/流式生成共享；同步满额返回 429，已接受的 SSE 请求以 error/query_busy 终止。额外最多 16 个实例内活动流注册，注册满额返回 429，无无限等待队列。
- `RAG_QUERY_TIMEOUT_SECONDS=180`，范围 1..300，包含流式检索和模型调用；供应商请求期限按剩余时间收紧。检索原有限额继续有效；短 SQL 事务仍单独 15 秒。同步路径在模型调用、阶段切换、返回前检查期限，单次 SQL 受独立事务期限约束。
- 查询、流式、取消请求均在 JSON 反序列化前限制 256 KiB，包括未知 Content-Length。供应商流总线数据最多 4 MiB，单个 SSE 数据帧/行最多 64 KiB，完整回答最多 200 万字符；UTF-8 严格解码。客户端限制单事件 4 MiB、整流 32 MiB，并支持 UTF-8 跨块、CRLF、多行 data。
- 重排最多 500 个候选且整体输入受所配上下文 token 上限保护；超限 413，不静默截掉候选。没有重排异常的隐藏回退。现有主机白名单、HTTPS 默认、禁止重定向、AES-GCM 密钥存储和敏感日志规则保持有效。
- 无数据库新结构，V1—V6 不改写。

## 验证

运行命令：

```sh
mvn -B -f backend/pom.xml verify
pnpm --dir frontend test
pnpm --dir frontend build
```

后端验证包括真实 PostgreSQL 18 + pgvector、固定上游重排样本、模型大小预算、预览、无证据、无回答缓存、引用合法/未知编号、捕获后模型/索引切换、协议增量在响应结束前到达、超时、截断流、POST 会话/CSRF、同用户不同会话不能取消他人流、显式取消关闭供应商连接、浏览器断开由心跳释放模型连接。前端验证 UTF-8 分割、终止事件、错误/EOF、取消、权限过期、中英文、草稿隔离、来源 emoji 高亮及 HTML 不执行。

本地结果（2026-10-27）：

- 完整后端 `verify`：150 项通过，0 失败/错误/跳过。前端 27 项通过，TypeScript/Vite 构建通过。
- 开发实际 JVM 与 prod 单镜像均通过真实 HTTP TXT/Markdown 上传、持久化索引、五模式同步回答、重排、提示词预览、SSE、共享来源和原文选区闭环；供应商为临时确定性协议服务。
- 使用独立 Python tiktoken 对实际预览中的 system/user 消息计算：2484 / 16000 token，包含配置输出预留 1536 和消息开销 32，与 Java answerBudget 相同。
- 实际 Chromium（非 jsdom）验证登录、流式回答、重排、来源 emoji 高亮、实际提示词预览、停止、断流失败提示和中英文切换。视觉检查后修正表单尺寸和旧阶段提示，再次验证。
- prod 使用 UID 10001、受限运行数据库角色；新数据库 V1→V6 冷启动成功，前端资源和后端同一镜像、第三方许可可读。最终镜像 `sha256:83b528fedb13a3ae38a8dc37f1e6dc432e8a0dbc9856d43dbaa16778f86976d1`；本地镜像按现有 runtime 阶段装配，使用已验证 Maven 包，未重新拉取全部构建依赖。
- V1—V6 无修改；开发数据、主密钥及原 jar/镜像在仓库目录外备份。验证后恢复原模型绑定，清除本阶段知识库/配置，关闭临时供应商、浏览器和冷启动容器；开发数据库及原数据卷保留。

真实供应商仍需由管理员在配置页填写密钥、绑定回答/抽取/摘要/向量（关键词可单独绑定）、上传自有中英文小样本并构建/激活索引，实测各模式回答、来源支持程度、限流与取消。可选重排另行验证兼容协议与阈值。不得将本地确定性协议服务当作真实模型质量验收。
