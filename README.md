# TjLightRAG

JDK 21 / Spring Boot 4.1.2 / Spring AI 2.0.2 / PostgreSQL 18 / React + TypeScript + Vite + Tailwind CSS。
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

前端 Vite 将 /api 代理至服务端 8080。默认 dev profile 连接 localhost:5432/tjlightrag，用户和密码均为 tjlightrag。
可使用 DB_URL、DB_USERNAME、DB_PASSWORD 覆盖。JPA 只验证结构，Flyway 执行迁移。

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

JUnit Jupiter 测试连接真实 PostgreSQL，覆盖 Flyway、JPA、错误密码、成功登录、CSRF 和注销。
前端 Vitest + jsdom 测试认证交互与语言切换。

## 单镜像部署

```sh
export DB_PASSWORD='替换为安全数据库密码'
docker compose -f docker/compose.yml up -d --build
```

前端构建产物嵌入 Spring Boot static 资源，同一镜像同源提供网页和 REST。
prod profile 必须设置 DB_URL、DB_USERNAME、DB_PASSWORD；默认 secure cookie，部署在可信 HTTPS 反向代理后。
仅本地 HTTP 验证时设置 COOKIE_SECURE=false。不要将该覆盖用于公网生产环境。
镜像导出：`docker save tjlightrag:local -o docker/images/tjlightrag.tar`；镜像归档不提交到 Git。
会话保存在内存，服务重启需要重新登录。当前没有注册、权限管理、RAG 或 AI 调用界面。

## 协作

参阅 AGENTS.md、docs/CODING_STANDARDS.md 和 DESIGN.md。禁止在 main 分支生成代码。
开发完成后先合并 main 再推送；本次初始化不自动提交或推送。
