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

- 模型配置为不可变版本，API 密钥只经 SecretVault 在服务端加密/解密，不回传密文或明文；日志与异常不得包含模型请求正文、密钥或租约令牌。
- 新检索必须一次捕获知识库 active_generation_id，图谱和三类向量均限定同一知识库/索引代；查询向量使用该代 embedding_version，不能用全局新绑定解释旧向量。V2 固定空间仅保留兼容，不与新代混用。
- 模型调用不占数据库长事务；过期租约不得发布成功或失败，取消/重试保留 fencing 检查。模型或抽取版本切换先构建完整新代，经验证后原子切换；失败保留旧代。

- 回答流必须传播供应商真实增量；只有完整成功才发送 done。错误、EOF、取消不得将部分内容标为成功；取消需释放模型订阅与 HTTP 连接。
- 首版回答缓存完全禁用。后续启用缓存须显式设计用户/知识库/索引代/模型与提示词/检索参数/历史隔离，不能直接复用旧表中的回答。
