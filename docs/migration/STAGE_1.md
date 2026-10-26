# 阶段 1：PostgreSQL + pgvector 与知识库边界

## 交付范围

实现知识库元数据/成员权限、数据库增量迁移、图对象原始存储和三类向量的读写/精确检索。不包含文档上传、token 分块、模型调用、LightRAG 抽取/图合并、五模式检索或问答界面；这些继续按阶段 2—6 实施。现有登录和前端空工作区保留。

## 数据库与向量空间

- PostgreSQL 18.6、pgvector 0.8.7，镜像固定为 `pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0`。
- 使用 trixie 与原 PostgreSQL 18 环境的系统排序规则保持一致；不要将切换基础发行版当作无影响的标签更新。
- V1 原文未改。V2 增加 9 张表：向量配置、知识库、成员、文本块、实体、关系及三个独立向量集合。所有新增表和字段都有 PostgreSQL COMMENT。
- 向量列为 `VECTOR(D)`，D 来自经验证的配置，范围 1..2000，对应所用 HNSW vector/cosine 索引限制。
- 开发配置明确使用 `fixture-3d`、3 维，方便固定向量验收，不是选定了真实模型，也不用于估算真实 embedding 维度。
- 生产 profile 必须设置 `RAG_EMBEDDING_MODEL` 和 `RAG_VECTOR_DIMENSIONS`，没有默认维度。该阶段不调用模型，标识只定义数据空间。
- `rag_vector_configuration` 记录模型身份和维度。`beforeMigrate.sql` 与启动检查共同阻止已有数据库的模型/维度漂移；不能以 Flyway repair、更换占位符或同维度模型替换来混用索引。后续需按阶段 3/6 显式迁移、重建和切换索引代。
- Flyway 版本化 SQL 校验和不包含解析后的占位符值，因此不能仅依赖校验和防止漂移；这里使用显式数据检查。
- 当前整个部署使用同一模型空间。文档修订、来源贡献、embedding_space/index_generation 等完整生命周期模型由后续阶段增量加入；阶段 1 的原始存储不得直接作为可发布索引。

## 存储与约束

JPA 管理 `KnowledgeBase` 和 `KnowledgeBaseMember`。`RagObjectStore` 使用绑定参数写入原始文本块、实体、无向关系；`PgVectorStore` 使用绑定参数写入和查询三个独立集合。两类存储加入同一 Spring 事务，回滚同时撤销 JPA 与 JDBC 修改。

| 表 | 关键约束 |
| --- | --- |
| rag_vector_configuration | 单例 ID=1，固定维度和非秘密模型标识；运行账户仅可读 |
| rag_knowledge_base | UUID 标识，非空且有长度限制的名称 |
| rag_knowledge_base_member | 知识库/用户外键，成员唯一，每库最多一个 OWNER，角色白名单 |
| rag_chunk | `(knowledge_base_id,id)` 复合主键，非空正文 |
| rag_entity | 复合主键、每库实体名称唯一 |
| rag_relation | 两个带知识库 ID 的实体外键，禁止跨库连接/自环；端点对唯一、规范顺序；权重有限且非负 |
| rag_chunk_embedding / rag_entity_embedding / rag_relation_embedding | 复合主键、指向所属对象的复合外键；维度固定、非零向量，分别建立 HNSW cosine 索引 |

服务层拒绝非有限值、零向量和错误维度；数据库也拒绝错误维度与零向量。关系以 UUID 的数据库兼容顺序去重，这只是存储端点规范化；后续对照上游实体名称输出时仍按 Unicode 码点排序。

## 精确检索语义

- `VectorKind` 是封闭枚举，仅允许三个固定表名；用户输入不进入 SQL 标识符。
- 每次读写都检查当前成员权限；查询 SQL 同时过滤 `knowledge_base_id`，不能只检查“用户能访问某个库”就搜索所有数据。
- `<=>` 是 cosine 距离，输出 `similarity = 1 - distance`，越大越相似。阈值为相似度，范围 [-1,1]，采用包含边界的 `>=`。
- 按 similarity 降序和 UUID 升序排序，limit 限制 1..100。
- 为阶段 0/1 建立精确基准，当前查询使用 MATERIALIZED 候选集，避免 HNSW 近似检索改变候选。HNSW 索引已创建，但 ANN 路径及召回评估留待阶段 4；不宣称当前精确全量扫描适合大规模生产负载。
- 向量对象必须先存在；不能写入其他知识库的对象。此阶段没有对浏览器开放原始向量写接口。

## 权限与 REST

所有 REST 路径以 `/api/knowledge-bases` 为父路径。沿用会话 Cookie、写请求 CSRF，用户身份只来自 Principal。没有全局管理员绕过知识库成员规则。

| 操作 | 接口 | 权限 / 返回 |
| --- | --- | --- |
| 创建 | POST / | 登录用户，自动成为唯一 OWNER；201 + Location |
| 列表 | GET /?page=0&size=20 | 仅返回所属库；size 1..100，page 0..100000 |
| 详情 | GET /{id} | OWNER/WRITER/READER；200 |
| 更新元数据 | PUT /{id} | OWNER；200 |
| 删除整库 | DELETE /{id} | OWNER；204，数据库级联清理所属对象/向量 |
| 授权/调整成员 | PUT /{id}/members/{username}，JSON `{"role":"READER"}` 或 `WRITER` | OWNER；204；目标必须是已有用户 |
| 撤销成员 | DELETE /{id}/members/{username} | OWNER；204；不能撤销所有者 |

- OWNER/WRITER 可以写原始对象与向量，READER 只能读取/检索。
- 非成员与不存在的库统一返回 404；已加入但权限不足返回 403；未登录 401、无效参数 400、唯一/FK 冲突 409；不回传 SQL 细节。
- 所有权转移不在本阶段，不能通过 grant 自升 OWNER。成员锁持续到事务结束，使撤权与存储操作有明确事务边界。
- 删除整库与阶段 6 的“删除一个文档并保留共享知识”不同；后者尚未实现。

## 开发与生产账户

pgvector 扩展须由 DBA 预装，或由具备扩展安装权限的迁移账户创建；不将该权限授予运行账户。

应用数据源使用受限运行账户 `tjlightrag_app`；Flyway 使用独立的迁移账户。`afterMigrate.sql` 仅在运行角色已存在时授予当前 schema 的 DML、sequence 使用权限；不授予 CREATE/DROP，不允许修改迁移历史和模型空间元数据。

新库由 `docker/init/01-runtime-user.sh` 创建运行角色，密码通过 psql 字面量引用转义，不在 SQL 源码拼接或日志输出。初始化脚本只在空数据目录自动执行，不改写已有角色密码。

本地开发默认值：

| 用途 | 用户 | 密码 | 连接 |
| --- | --- | --- | --- |
| 运行 DML | tjlightrag_app | tjlightrag-app | localhost:5432/tjlightrag |
| Flyway 迁移 | tjlightrag | tjlightrag | 同库 |

以上仅用于隔离的本地开发。生产需要分别设置 `DB_USERNAME/DB_PASSWORD` 与 `DB_MIGRATION_USERNAME/DB_MIGRATION_PASSWORD`；Compose 固定两个角色名，密码分别配置。实际组织可把迁移拆成独立部署步骤，不向长期运行应用保留迁移权限。

## 启动、已有库升级与回滚

```sh
docker compose -f docker/compose.dev.yml up -d --wait
# 已有数据卷不会自动执行 init 脚本；确认 APP_DB_PASSWORD 与应用配置一致后补建运行角色：
docker compose -f docker/compose.dev.yml exec -T db bash /docker-entrypoint-initdb.d/01-runtime-user.sh
cd backend
mvn verify
mvn -pl bootstart spring-boot:run
```

旧库升级前先 pg_dump，并验证备份可读；保留原数据卷，不执行 down -v。已有 V1 用户与密码摘要由增量迁移原样保留。运行角色补建脚本幂等，但不负责密码轮换。

如镜像基础发行版发生变化，应比较 `pg_database.datcollversion` 与 `pg_database_collation_actual_version(oid)`。本次开发库从旧环境升级时已保存 `/workspace/backups/tjlightrag-before-stage1.dump`，改用匹配的 trixie 镜像，并在服务停止期间重建索引，确认排序规则匹配和原用户保留。生产需按 PostgreSQL 官方流程处理排序规则升级，不能直接忽略警告或只改版本号。

生产填写 `docker/.env.example` 中所需值至本地 `docker/.env`，再运行：

```sh
docker compose --env-file docker/.env -f docker/compose.yml up -d --build
```

使用真实模型前，在全新数据库确定正确模型/维度。不要把已存在的 3 维开发库直接切换成真实模型库。回滚迁移应恢复已验证的备份与兼容应用版本，不改写 V1/V2，也不清空数据卷来掩盖升级失败。

## 验证命令

```sh
# 在云环境先 source docs/scripts/cloud-env.sh；普通开发机使用已安装的 JDK 21 / Maven。
cd backend
mvn -B -pl bootstart -am \
  -Dtest=VectorStorageIntegrationTest,MigrationIntegrationTest,VectorSettingsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B verify
```

Testcontainers 使用同一固定镜像，运行真实 PostgreSQL/pgvector，不使用 H2，也不会在 Docker 不可用时静默跳过。新增用例覆盖：三类精确向量排序/阈值/更新、跨库过滤、读写与撤权、跨库外键、非法维度/非有限值/零向量、唯一键、混合事务回滚、表/字段注释、HNSW 索引、HTTP/CSRF、空库迁移、V1 用户保留、重复迁移、模型/维度漂移和受限运行角色。

## 本次执行结果（2026-10-26）

| 检查 | 结果 |
| --- | --- |
| 新增 Testcontainers 存储集成 | VectorStorageIntegrationTest：9 项通过（含三种向量族） |
| 空库/V1 升级/自动账户初始化 | MigrationIntegrationTest：3 项通过；执行真实 bootstrap 脚本并认证受限账户 |
| 配置校验 | VectorSettingsTest：2 项通过 |
| 完整后端 | mvn verify：71 项通过，0 失败、0 错误、0 跳过；打包成功 |
| 前端回归 | 5 项通过；TypeScript/Vite 生产构建成功 |
| 开发库实际升级 | 升级前已备份并确认备份可读；V1 原文未修改，原用户保留，排序规则匹配 |
| 实际开发链路 | Vite → 后端：登录、创建/读取/更新/分页列表/删除知识库、删除后 404 均通过 |
| 单镜像与空库冷启动 | tjlightrag:local 构建成功；全新数据库自动初始化运行角色，prod profile 完成 V1/V2，运行账户为 tjlightrag_app；HTML/JS/CSS 与知识库创建/读取/删除通过 |
| Compose/脚本 | dev/prod 配置校验、初始化脚本语法与重复执行通过；环境启动说明已保存为草稿，未发布 |

冷启动暴露的工作区绑定目录权限已修复（初始化目录/脚本 0755），并在 Testcontainers 中增加自动执行真实脚本的覆盖。一轮验证受到构建缓存占满磁盘影响；清理可重建缓存、保留源码/备份/用户数据后重跑，以上为最终成功结果。未删除既有数据库卷。

镜像归档已更新至 docker/images/tjlightrag.tar（Git 忽略），旧归档保留在 /workspace/backups/tjlightrag-before-stage1-image.tar。生产 profile 的冷启动仅在隔离测试库使用 fixture-3d 与本地 HTTP 临时关闭 Secure cookie，不代表真实 embedding 模型或公网 HTTPS 已验证。

阶段 0 的语料与比较框架测试继续保留，不能用阶段 1 的存储成功替代算法对照。

开发时重新打包会替换 target 中的 jar，运行进程不应继续从该路径加载旧类。云实例现已将验证后的 jar 复制到 /workspace/run/TjLightRAG/app.jar 再启动；更新时先停止本项目旧进程，再替换运行副本并重启。最终后端及 Vite 代理的未认证知识库请求均返回 401。
