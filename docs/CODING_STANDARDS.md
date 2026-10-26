# 代码规范

## Java

JDK 21；包名 com.trs。common 放通用类型，app 放业务与持久化，bootstart 负责启动和配置。
实体使用 Lombok Getter/Setter/NoArgsConstructor，避免 Data 自动输出密码字段。
所有类和公共方法增加 Javadoc，author 使用当前系统用户名。
REST 通过会话认证，写请求验证 CSRF，密码只存 BCrypt 摘要。日志使用 SLF4J，不输出凭据。

## 数据库

PostgreSQL 18，JPA 仅 validate，结构由 Flyway 管理。迁移命名 V数字__说明.sql。
SQL 关键字大写，标识符小写 snake_case；必须为每个表和字段添加 COMMENT ON。
已执行的迁移不可改写；生产修改默认管理员密码后再开放服务。

## 前端

React + TypeScript strict，Vite，Tailwind CSS。可见文案通过 i18next 国际化。
保持表单语义和键盘可访问性；网络请求提供错误与等待反馈；不在客户端持久化密码或会话令牌。

## 测试和分支

先测试后实现。后端 JUnit Jupiter 集成测试验证实际 PostgreSQL、Flyway、JPA、登录和注销。
前端 Vitest/jsdom 验证语言切换、认证与失败反馈。提交前运行测试及生产构建。
main 不得生成代码；开发分支测试完成后合并 main 再推送。任何推送需要用户授权。
