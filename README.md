# TjLightRAG

JDK 21 / Spring Boot 4.1.2 / Spring AI 2.0.2 / PostgreSQL 18 + pgvector 0.8.7 / React + TypeScript + Vite + Tailwind CSS。
版本来自 Maven Central 最新稳定版（2026-10-26），不采用 RC/M 预发布版本。
Spring AI 已接入文本/向量模型兼容协议适配，可配置模型、抽取实体关系并构建/切换图谱与向量索引。检索和问答留待后续阶段；文档解析与分块不需要模型密钥。

## 目录

- backend：Maven 父项目，common（通用代码）、app（业务）、bootstart（启动与配置）
- frontend：pnpm 前端，中英文登录、知识库、文档/任务、模型配置及索引管理
- docs：代码规范
- docker：前后端同一应用镜像，数据库独立容器，Compose 部署

## 本地开发

需要 Java 21、Maven 3.9+、Node 24、pnpm 11.25.0、Docker Compose。

```sh
docker compose -f docker/compose.dev.yml up -d --wait
cd backend
mvn clean install
mvn -pl bootstart spring-boot:run
```

另开终端：

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

前端 Vite 将 /api 代理至服务端 8080。默认 dev profile 连接 localhost:5432/tjlightrag；运行账户为 tjlightrag_app / tjlightrag-app，Flyway 迁移账户为 tjlightrag / tjlightrag。
可使用 DB_URL、DB_USERNAME、DB_PASSWORD 和独立的 DB_MIGRATION_USERNAME、DB_MIGRATION_PASSWORD 覆盖。JPA 只验证结构，Flyway 执行增量迁移。开发向量空间为明确的 fixture-3d / 3 维；生产必须显式配置历史固定空间标识与维度（新部署可用 legacy-bootstrap / 3）。本阶段实际模型在管理员配置页选择，与历史空间区分，切换实际模型不修改历史空间参数。已有数据卷的运行角色初始化见 docs/migration/STAGE_1.md。

默认登录：admin / 123456。密码使用 BCrypt 存储；生产部署前应更换默认管理员密码。
认证使用 HttpOnly、SameSite=Lax 会话 Cookie；写请求先 GET /api/auth/csrf，使用 X-CSRF-TOKEN 请求头。
登录 POST /api/auth/login（表单），用户 GET /api/auth/me，注销 POST /api/auth/logout。

## 测试

数据库集成测试使用独立 Testcontainers，需要可用 Docker。运行：

```sh
cd backend
mvn test
cd ../frontend
pnpm test
pnpm build
```

JUnit Jupiter 测试连接真实 PostgreSQL，覆盖 Flyway、JPA、错误密码、成功登录、CSRF 和注销。新增存储测试使用 Testcontainers，需要可用 Docker，真实验证 PostgreSQL 18 + pgvector、迁移及知识库权限。
前端 Vitest + jsdom 测试认证交互与语言切换。

## 单镜像部署

```sh
cp docker/.env.example docker/.env
# 编辑 docker/.env，填写两套数据库密码、模型标识与实际维度
docker compose --env-file docker/.env -f docker/compose.yml up -d --build
```

前端构建产物嵌入 Spring Boot static 资源，同一镜像同源提供网页和 REST。
prod profile 必须设置运行/迁移两套数据库凭据、RAG_EMBEDDING_MODEL、RAG_VECTOR_DIMENSIONS；默认 secure cookie，部署在可信 HTTPS 反向代理后。
仅本地 HTTP 验证时设置 COOKIE_SECURE=false。不要将该覆盖用于公网生产环境。
镜像导出：`docker save tjlightrag:local -o docker/images/tjlightrag.tar`；镜像归档不提交到 Git。
会话保存在内存，服务重启需要重新登录。已提供知识库及成员权限、三类向量原始存储、TXT/Markdown 导入、持久化任务及真实 BPE 分块。已接入模型配置、实体关系抽取、embedding 生成和索引构建/原子切换；RAG 检索与问答尚未开放。“已分块”只表示文档预处理完成；请另行构建并激活索引。详见 docs/migration/STAGE_2.md 和 docs/migration/STAGE_3.md。

## 协作

参阅 AGENTS.md、docs/CODING_STANDARDS.md 和 DESIGN.md。禁止在 main 分支生成代码。
开发完成后先合并 main，再按用户授权推送。

## 文档导入

登录后创建/选择知识库，支持粘贴文本和上传 UTF-8 TXT/Markdown（最多 5 MiB）。上传返回后台任务；可查看状态、取消/重试，完成后预览原文、章节与分块来源。默认 cl100k_base、512 token 窗口和 64 token 重叠，不需要模型密钥。

原文与任务保存到 PostgreSQL，服务重启后通过租约恢复。`RAG_INGESTION_ENABLED=false` 可暂停当前实例 worker 而保留队列。生产分块参数和版本限制、不可变修订/去重、历史修订及错误码见 docs/migration/STAGE_2.md。

## 模型配置与切换

部署时通过安全配置注入 `RAG_MODEL_MASTER_KEY`（Base64 的 32 字节随机 AES 密钥）并设置 `RAG_MODEL_ALLOWED_HOSTS`（允许服务主机名，逗号分隔）。主密钥默认不生成，缺失时无法保存非空 API 密钥；应与数据库备份一起保留，不能在重启时更换。默认仅 HTTPS；可信本地开发可设置 `RAG_MODEL_ALLOW_HTTP=true`。不要把密钥或含密钥的 .env 提交到 Git。

管理员登录后进入顶部“模型配置”，填写兼容服务地址、模型名、API 密钥及限制，执行连接测试，绑定抽取/摘要/向量用途；重排可选，查询重排和问答将在后续接入。保存编辑产生新版本，密钥只返回是否已配置。

在知识库页面上传文档并等待“已分块”，再“构建新索引”。新代完整构建后手动激活；旧代继续保留，可再次激活回退。相同维度的不同模型也需重建；不同维度按索引代隔离，支持 1..2000 维。参数、接口、算法差异和验收见 docs/migration/STAGE_3.md。
