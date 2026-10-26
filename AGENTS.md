# 开发约定

- 禁止在 main 分支生成或修改代码；开发前检查分支，使用 develop 或 feature/*。
- 推送到服务器前，先将经测试的开发分支合并到 main，再推送 main；未经用户授权不得推送。
- 云任务已隔离，使用现有检出目录；除非用户明确要求，不创建 Git worktree。
- 实现功能前先编写测试：后端 junit-jupiter，前端 Vitest + jsdom。
- JavaBean 对象使用 Lombok 注解，减少手写 getter/setter。
- Java 类和公共方法必须提供注释，包含 @author，值为生成代码时的系统用户名。
- 包名前缀 com.trs，JDK 21，Maven 多模块：common → app → bootstart。
- SQL 关键字大写，表和字段名小写；PostgreSQL 迁移必须有表和字段 COMMENT。
- Flyway 已执行迁移不可修改；新增版本迁移。
- 不记录密码、会话、CSRF 令牌等敏感信息，日志使用 SLF4J。
- 依赖版本使用明确版本和锁文件；前端使用 pnpm install --frozen-lockfile。
- 遵守 docs/CODING_STANDARDS.md 与 DESIGN.md。
