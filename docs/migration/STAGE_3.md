# 阶段 3：模型配置、实体关系抽取与图谱合并

状态：工程交付及本地验收完成；真实供应商/模型尚未选定，生产模型质量与限流需另行联调。

## 扩充范围

管理员配置文本、向量、重排模型；首批支持 OpenAI-compatible chat/embedding 和兼容 `/rerank` 协议，其他协议需独立适配。配置不可变版本化，密钥以服务端 AES-GCM 加密，不回传浏览器；使用部署环境主密钥，未配置时不能写入密钥。服务地址由部署允许主机清单限制，默认只允许 HTTPS，显式允许的本地测试服务除外。

全局角色绑定：extract、summary、keywords、answer、embedding、rerank；重排可不绑定。阶段 3 真正调用抽取/摘要/向量，关键词/回答和查询重排留待阶段 4/5。管理员与知识库 OWNER 的权限分别检查。

## 构建与切换

从当前已分块修订捕获完整知识库快照；为每个构建保存模型配置版本、提示词、tokenizer 与来源。按共享块一次抽取，保存逐块贡献/检查点，有限补充抽取。基于固定 LightRAG 上游移植字段解析、名称归一、同名节点/无向关系合并、描述去重和摘要；三类向量独立保存。

后台队列采用数据库租约和 fencing token；模型调用不占数据库事务。构建中间状态不对查询发布；全部来源、图谱、向量完整性通过后 ready。OWNER 明确激活 ready 代，使用期望当前代进行乐观检查并原子替换指针。失败/取消不改变当前代；可选择保留的成功代回退。相同维度不同模型也不混检。

使用新增按知识库/索引代隔离的向量表，列为 pgvector VECTOR，每行携带维度并 CHECK，复合外键约束到捕获的索引代/维度。V2 固定维度存储保留兼容，阶段 3 不对旧表重写或混用；不同维度不再要求覆盖旧表。阶段 4 在具体模型空间增加 ANN，不在可变维度列直接建不合法 HNSW 索引。

## 验收要求

先生成 JUnit/Vitest 测试，再实现。使用真实 PostgreSQL 和本地协议模拟服务验证加密/脱敏/权限、协议调用、抽取合并、并发与恢复、三类向量及跨模型/跨维度切换。固定响应只证明算法和接口，真实供应商质量/限流另行验收；不把替身结果声称为真实模型调用。

## 配置与安全边界

- V5 增加 `app_user.is_admin`，将既有 `admin` 初始化为平台管理员；普通登录用户不因拥有知识库而取得模型管理权。管理员接口在 Spring Security 检查 ADMIN，并在服务层再次查询当前管理员标记。
- `RAG_MODEL_MASTER_KEY`：Base64 编码的 32 字节 AES 主密钥，使用部署密钥管理注入；必须与数据库一起备份、保留，不能随重启随机更换。缺失时系统仍能管理文档，但不能保存/解密非空模型密钥。配置列表、历史、测试和构建接口均不返回密钥或密文。
- `RAG_MODEL_ALLOWED_HOSTS`：逗号分隔的精确主机名清单，无协议和路径；默认空，禁止向未授权目标发送凭据。地址禁止 userinfo、query、fragment 和重定向。
- `RAG_MODEL_ALLOW_HTTP`：默认 false；仅可信本地/内网开发服务可显式设置 true，仍需主机清单。生产建议 HTTPS。模型协议路径分别为基础地址加 `/chat/completions`、`/embeddings`、`/rerank`，例如基础地址 `https://api.example.com/v1`。
- 实际生成通过版本隔离的 Spring AI `ChatModel`、`EmbeddingModel` 适配器调用兼容协议；不是将 HTTP 响应伪装成固定响应。阶段 3 不启用工具执行或流式生成。
- 每次请求有 1..120 秒完整响应期限、4 MiB 响应上限及取消；拒绝 length/tool_calls 等未正常完成的 chat 响应。向量检查响应索引、维度、有限值和非零范数。
- token 预算使用明确的 cl100k_base。配置上下文/输出上限需按供应商和本地样本保守设定，不宣称这一 tokenizer 等同所有模型原生 tokenizer；未适配协议须增加独立适配器和测试。
- 新配置/编辑在 UI 保存为不可变版本；空 API 密钥保留旧密钥，勾选清除才移除。全局用途绑定指向具体版本，不能静默跟随某配置的最新版本。连接测试会实际请求模型，可能产生供应商费用。

`RAG_EMBEDDING_MODEL` / `RAG_VECTOR_DIMENSIONS` 继续用于既有 V2 固定空间及启动一致性校验；不用于选择本阶段实际供应商，也不应随着 UI 模型切换而改变。新索引维度从所绑定模型版本捕获，支持 1..2000 维，按代隔离；V2 固定表保留，阶段 3 不混写。新部署可显式给历史空间配置 `legacy-bootstrap/3`，真实模型在模型配置页选择。

## REST 与页面

| 接口 | 权限 / 行为 |
| --- | --- |
| GET /api/admin/models、/status | 平台管理员；配置历史版本（最多 500 条）、部署就绪信息，均脱敏 |
| POST /api/admin/models | 创建配置与首个版本 |
| PUT /api/admin/models/{profileId} | 增加版本，不改写旧版本；数据库触发器拒绝 UPDATE 旧版本 |
| GET /api/admin/models/{profileId}/versions | 配置版本历史 |
| POST /api/admin/models/versions/{versionId}/test | 按配置类型发起有界真实协议测试 |
| GET、PUT /api/admin/models/bindings | 读写用途到版本的绑定；可用 null 解绑 |
| POST /api/knowledge-bases/{kb}/indexes | 知识库 OWNER；202 返回持久化 pending 构建，不等待模型 |
| GET /api/knowledge-bases/{kb}/indexes、/{id} | 库成员；最多 50 代并始终包含当前激活代、分块进度及版本信息 |
| POST /api/knowledge-bases/{kb}/indexes/{id}/activate | OWNER；请求 expectedActiveGenerationId（首次 null），原子激活或回退；并发指针变化返回 409 |
| POST /api/knowledge-bases/{kb}/indexes/{id}/cancel、/retry | OWNER；持久化取消、按原配置版本重试 |
| GET /api/knowledge-bases/{kb}/indexes/{id}/graph | 库成员；成功代的实体/关系各最多 100 条样本，不代表全图浏览 |

前端管理员顶部进入“模型配置”；知识库页面提供构建、进度、失败原因码、取消/重试、激活/回退和纯文本图谱样本。普通成员无模型管理入口；READER/WRITER 不能构建或切换索引，后台独立校验。页面文案提供中英文；密钥不写 localStorage/sessionStorage。

## 算法与持久化契约

1. 提示词从固定上游 `lightrag/prompt.py` 提取，文件位于 `backend/app/src/main/resources/rag/prompts`，PROVENANCE.json 保存提交、源文件哈希与版本。默认 English 与上游一致，专有名词不翻译。渲染后的初始/补充提示词与阶段 0 冻结样本逐字对照。
2. 每块输出最多 100 条有效记录、40 个实体；entity 四字段、relation/relationship 五字段，关系没有模型输出权重，贡献权重为 1。归一包含全角、中文间空格、外层引号、类型首个非空逗号分项及小写；拒绝自环、保留英文大小写。
3. 补充抽取最多一次，携带初始用户/助手历史；上下文加输出预算不足时跳过补充。相同实体/无向关系选择描述更长的版本，不将补充视为第二次权重贡献。
4. 生产比上游警告路径更严格：缺少 COMPLETE、输出截断或超过记录上限使构建失败，不发布部分图；不移植所有容错猜测格式或 JSON 抽取路径。实体名上限 512 字符，描述 16000，关系关键词 4000，超限记录拒绝。
5. 每个共享块每代保存一份 JSONB 贡献和块向量，短事务原子 checkpoint。多个文档复用块不会重复抽取/增加权重；文档修订及完整来源关联通过索引文档快照和既有修订来源表保留。
6. 从所有 checkpoint 重新合并：描述精确去重，来源不截断；类型按块投票，多数优先，同票按字典序（为可复现明确适配，不依赖异步先后）。关系端点缺失时补 unknown 节点。无向边标准化，权重为不同贡献块数，重试不会累加旧结果。
7. 描述少于 10 项且不超过 1000 token 时直接拼接 `<SEP>`；其余使用固定上游摘要模板，预算内分组、必要时分层摘要，最多 8 轮。不静默截断，不能收敛则失败；这属于本平台显式预算适配，不声称与任意上游配置的摘要策略完全一致。图上限 10000 节点、30000 边、20000 共享块，超限明确失败。
8. 块、实体、关系的向量分别保存在 `rag_index_chunk_vector`、`rag_index_entity_vector`、`rag_index_relation_vector`；向量内容按上游块正文、`名称\n描述`、`关键词\t首端点\n末端点\n描述` 生成。每行维度通过 CHECK 和索引代复合外键校验，模型版本不可跨代解释。
9. 数据库 SKIP LOCKED 领取、45 秒租约、5 秒心跳、fencing token；一次实例最多一个构建，多个实例可并行且不重复领取。最多 3 次自动领取，暂时 HTTP/限流/超时错误隔 5 秒有限重试；人工重试重置次数并复用 checkpoint。`RAG_INDEXING_ENABLED=false` 暂停当前实例派发，不清理队列。
10. 合并/摘要/向量计算不占数据库事务；最终图和实体/关系向量在一个事务写入，检查三类数量/外键、来源 checkpoint 和当前权限/租约后 ready。构建失败、取消或旧 worker 回来都不能替换当前激活代。
11. 新模型（含同维度不同模型）或抽取提示词变更须新建全量索引；建成后 OWNER 原子切换。已完成代保留，按期望指针重新激活即可回退。模型 key 外部失效不破坏已有向量，但用旧版本重试可能失败。仅回答/查询重排模型变更的查询行为和缓存隔离留待阶段 4/5，不在本阶段假装执行。

## 验证

后端执行 `source docs/scripts/cloud-env.sh && cd backend && mvn -B verify`；前端执行 `pnpm test && pnpm build`。所有数据库集成测试使用独立 Testcontainers（包括原有登录测试），禁止 worker 背景污染其他测试或开发队列。

后端最终 `mvn -B verify`：109 项通过，0 失败、0 错误、0 跳过；前端 Vitest + jsdom：16 项通过，TypeScript/Vite 生产构建通过。原 V1—V4 和阶段 0 冻结语料均保持不变；空库 V1—V5 及 V1 升级、账户权限验证通过。

实机验证分两组完成：开发 JVM 及生产 profile 单镜像均在 worker 暂停时将构建持久化为 pending，HTTP 分别约 0.023/0.025 秒返回；真正重启 JVM/重建应用容器后恢复构建。测试文档去重为 2 个共享块，生成 2 个实体与 1 条权重为 2 的关系；实际通过加密配置、三类协议连接测试、3 维与 4 维模型空间隔离、激活及回退。每组测试恢复原用途绑定，删除自己的知识库与配置，保留原有开发数据库卷。部分 checkpoint 失败后复用、过期旧 worker 成功/失败双向 fencing、同维度不同模型、并行构建、错误向量和管理员 HTTP 边界由真实 PostgreSQL 集成测试独立验证，没有把 pending 重启实验写成中途强杀模型请求的实验。

最终镜像 `tjlightrag:local` 为 `sha256:464607bf79c392cb9cbf27a6e17cd6d0ee833a41315aed54de4cb67702901059`。冷启动数据库迁移 V1—V5 成功，受限运行账号完成所有操作，应用 UID 10001；网页 JS/CSS 与本次前端构建一致，第三方许可可读。完整 Dockerfile 源构建已验证；最终两处 REST/fencing 修正后使用通过完整回归的 Maven jar、当前前端产物和相同 Dockerfile 最终运行层组装镜像，避免反复下载构建依赖耗尽磁盘。镜像归档已更新至忽略文件 `docker/images/tjlightrag.tar`，原阶段 2 归档另行备份。仅清理了本任务的构建缓存与无用应用镜像，未删除既有命名数据卷。

本地开发主密钥保存在 Git 外 `/workspace/run/TjLightRAG/model-master-key`，权限 0600，重启复用；此文件不能作为公开制品或提交。阶段 3 实机测试前的数据库快照位于 `/workspace/backups/tjlightrag-stage3-before-live-smoke.dump`，该快照已包含 V5；不把它声称为 V5 升级前备份。固定模型响应只证明算法和兼容协议，不代表真实供应商质量、生产限流或 RAG 问答验收；本阶段没有选择或内置任何具体供应商的默认真实模型。
