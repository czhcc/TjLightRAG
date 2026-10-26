# 初始化验证记录

日期：2026-10-26；开发分支：develop。测试先于功能实现创建。

- JDK 21、Spring Boot 4.1.2、Spring AI 2.0.2；Maven verify 成功。
- JUnit Jupiter：1 个集成测试，0 失败、0 错误、0 跳过；真实 PostgreSQL 18.6，Flyway V1 与 JPA 校验通过。
- 后端认证：未认证 401、错误密码 401、正确密码登录、当前用户查询、无 CSRF 注销 403、有效注销 204、会话失效 401。
- Vitest + jsdom：2 个测试通过；覆盖中英文、登录注销、错误密码反馈。
- TypeScript 检查及 Vite 生产构建成功；pnpm 冻结锁文件安装成功。
- docs/scripts/setup-cloud.sh 已执行成功；安装后重新执行后端测试通过。
- Docker Compose 开发数据库启动 healthy；生产 Compose 配置校验通过。
- docker/Dockerfile 已构建 tjlightrag:local；前后端资源在同一应用镜像中。
- 镜像以 prod profile 启动，网页、JS/CSS、登录、查询当前用户、注销和会话失效均验证成功。
- 生产 profile 容器验证仅在本地 HTTP 上临时关闭 secure cookie；正式部署保留 secure cookie，使用 HTTPS。
- Vite 到后端的代理链路已实际验证登录、查询和注销。

云环境初次失败原因：基础机器只有 JRE，主目录不可写，Maven 需要显式代理及平台 CA，BuildKit 缺少代理主机解析。均通过外部工具目录、缓存路径、CA 信任和构建时解析配置解决；未关闭签名、校验和或 TLS 校验。

未验证：真实模型供应商调用（初始化未要求 AI 功能），公网 HTTPS 反向代理，以及发布后的新任务恢复。未提交、合并或推送代码。
