# TjLightRAG

JDK 21 / Spring Boot 4.1.2 / Spring AI 2.0.2 / PostgreSQL 18 + pgvector 0.8.7 / React + TypeScript + Vite + Tailwind CSS。
版本来自 Maven Central 最新稳定版（2026-10-26），不采用 RC/M 预发布版本。
Spring AI BOM 和 model 模块已接入；当前没有 AI 功能，不需要模型密钥，也不自动连接供应商。

## 目录

- backend：Maven 父项目，common（通用代码）、app（业务）、bootstart（启动与配置）
- frontend：pnpm 前端，支持中英文登录与注销，登录后空白工作区
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
可使用 DB_URL、DB_USERNAME、DB_PASSWORD 和独立的 DB_MIGRATION_USERNAME、DB_MIGRATION_PASSWORD 覆盖。JPA 只验证结构，Flyway 执行增量迁移。开发向量空间为明确的 fixture-3d / 3 维；生产必须提供所选模型标识与真实维度。已有数据卷的运行角色初始化见 docs/migration/STAGE_1.md。

默认登录：admin / 123456。密码使用 BCrypt 存储；生产部署前应更换默认管理员密码。
认证使用 HttpOnly、SameSite=Lax 会话 Cookie；写请求先 GET /api/auth/csrf，使用 X-CSRF-TOKEN 请求头。
登录 POST /api/auth/login（表单），用户 GET /api/auth/me，注销 POST /api/auth/logout。

## 测试

先启动开发数据库，再运行：

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
会话保存在内存，服务重启需要重新登录。已提供知识库及成员权限 REST、三类向量原始存储与精确检索；当前没有注册、文档上传、LightRAG 算法或 AI 问答界面。接口与升级说明见 docs/migration/STAGE_1.md。

## 协作

参阅 AGENTS.md、docs/CODING_STANDARDS.md 和 DESIGN.md。禁止在 main 分支生成代码。
开发完成后先合并 main，再按用户授权推送。
