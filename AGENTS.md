# 开发约定

- 禁止在 main 分支生成或修改代码；开发前检查分支，使用 develop 或 feature/*。
- 推送到服务器前，先将经测试的开发分支合并到 main，再推送 main；未经用户授权不得推送。
- 云任务已隔离，使用现有检出目录；除非用户明确要求，不创建 Git worktree。
- 实现功能前先编写测试：后端 junit-jupiter，前端 Vitest + jsdom。
- JavaBean 对象使用 Lombok 注解，减少手写 getter/setter。
- Java 类和公共方法必须提供注释，包含 @author，值为生成代码时的系统用户名。
- 包名前缀 com.trs，JDK 21，Maven 多模块：app 依赖 common 和 document-parser，agent 依赖 app，bootstart 依赖 app 和 agent；document-parser 独立负责文档解析，不依赖 app 或 bootstart。app 通过业务接口调用智能体，不反向依赖 agent。
- SQL 关键字大写，表和字段名小写；PostgreSQL 迁移必须有表和字段 COMMENT。
- Flyway 已执行迁移不可修改；新增版本迁移。
- 不记录密码、会话、CSRF 令牌等敏感信息，日志使用 SLF4J。
- 依赖版本使用明确版本和锁文件；前端使用 pnpm install --frozen-lockfile。
- 遵守 docs/CODING_STANDARDS.md 与 DESIGN.md。
- 涉及菜单项相关功能的新增、修改或删除时，必须同步更新 docs 目录下对应的“菜单名设计.md”和“菜单名实现.md”，确保设计方案、实现说明及主要类图和时序图与代码保持一致。

- 模型配置为不可变版本，API 密钥只经 SecretVault 在服务端加密/解密，不回传密文或明文；日志与异常不得包含模型请求正文、密钥或租约令牌。
- 新检索必须一次捕获知识库 active_generation_id，图谱和三类向量均限定同一知识库/索引代；查询向量使用该代 embedding_version，不能用全局新绑定解释旧向量。V2 固定空间仅保留兼容，不与新代混用。
- 模型调用不占数据库长事务；过期租约不得发布成功或失败，取消/重试保留 fencing 检查。模型或抽取版本切换先构建完整新代，经验证后原子切换；失败保留旧代。

- 回答流必须传播供应商真实增量；只有完整成功才发送 done。错误、EOF、取消不得将部分内容标为成功；取消需释放模型订阅与 HTTP 连接。
- 首版回答缓存完全禁用。后续启用缓存须显式设计用户/知识库/索引代/模型与提示词/检索参数/历史隔离，不能直接复用旧表中的回答。


## 1. 项目技术栈

本项目采用以下技术栈：

- Java 21
- Spring Boot、Spring AI
- Maven 多模块：`backend/common`、`backend/document-parser`、`backend/app`、`backend/agent`、`backend/bootstart`
- Lombok
- Spring Data JPA、PostgreSQL、pgvector、Flyway
- 前端：`frontend`，使用 React、TypeScript、Vite、pnpm、Vitest 和 jsdom

服务端父 POM 为 `backend/pom.xml`，仓库根目录不是 Maven 工程目录。依赖和插件版本以各 POM 及前端锁文件为准。
`document-parser` 独立负责文档解析，不依赖 `app` 或 `bootstart`；主要 Spring Boot 集成测试位于 `backend/bootstart/src/test/java`。
`agent` 的实现包为 `com.trs.agent`，负责 ReAct、工具、上下文管理和调用跟踪，智能体单元测试位于该模块；问答调用与跟踪终态契约在 `app` 中定义，`bootstart` 组装实现。不得通过让 `app` 依赖 `agent` 引入循环依赖。

所有新增代码必须兼容项目实际使用的依赖版本。

禁止擅自升级 Java、Spring Boot 或其他依赖版本。

## 2. Java 代码格式规范

### 2.1 基本格式

- 使用 UTF-8 编码。
- 使用 4 个空格作为基础缩进单位。
- 禁止使用 Tab 缩进。
- 建议每行不超过 120 个字符。
- 使用 Unix 风格换行符 LF。
- 文件末尾保留一个换行符。
- 删除行尾多余空格。
- 大括号采用 K&R 风格，左大括号不单独换行。
- if、for、while 等控制结构必须使用大括号。
- 不允许无意义的连续空行。
- Java 文件的最终格式以 Spotless 配置为准。

### 2.2 Java 格式化器

本项目使用：

- Spotless Maven Plugin 3.10.4
- Palantir Java Format 2.102.0
- PALANTIR 格式风格

配置统一放在 `backend/pom.xml` 的 `build/plugins` 中，由子模块继承；不在子模块重复配置或擅自更换格式化器。
当前 `formatJavadoc=false`，不要求格式化器重新排版 Javadoc；注释内容仍应遵守本文件的注释规范。

禁止使用 Google Java Format 的 GOOGLE 风格，
避免将代码转换为 2 空格缩进。

不允许手工修改已格式化代码来对抗格式化器。

### 2.3 import 规范

- 新增或修改的导入使用明确类名，避免普通及静态通配符 import；现有通配符可保留，当前 POM 未启用 `forbidWildcardImports`。
- 删除未使用的 import。
- 不使用没有必要的完全限定类名。
- 当前 Spotless 配置启用了 `removeUnusedImports`，未单独配置 `importOrder`；不额外进行无关的导入排序调整。

### 2.4 命名规范

- 类和接口：PascalCase
- 方法和变量：camelCase
- 常量：UPPER_SNAKE_CASE
- 包名：全部小写
- 枚举类型：PascalCase
- 枚举常量：UPPER_SNAKE_CASE

禁止使用无意义的名称，例如：

- data1
- temp2
- obj
- list1

允许在很短的局部作用域内使用常见变量名称。

## 3. Java 编码规范

### 3.1 类设计

- 遵循单一职责原则。
- 优先保持现有架构。
- 不新增不必要的抽象层。
- 不为简单功能创建复杂的设计模式。
- 不在 Controller 中编写复杂业务逻辑。
- 不在 Repository 中编写业务流程。

### 3.2 方法设计

- 方法应尽量只负责一项明确的工作。
- 避免过深的条件嵌套。
- 优先使用提前返回减少嵌套。
- 避免重复代码。
- 不捕获异常后直接忽略。
- 不随意使用 RuntimeException 代替业务异常。
- 不随意引入 Optional、Stream 或反射。

### 3.3 注释规范

- 对外公开 API 应有必要的 Javadoc。
- 复杂业务逻辑应有说明。
- 注释解释原因、约束和业务规则，而非重复代码。
- 新增业务注释优先使用中文。
- 不删除仍然有效的历史注释。
- 不生成无意义的模板注释。

## 4. Spring Boot 开发规范

### 4.1 分层

遵循项目现有分层方式：

- Controller：HTTP 接口和参数校验
- Service：业务逻辑
- Repository：数据访问
- DTO：接口输入输出
- Entity：持久化对象
- Config：配置
- Exception：异常处理

不强制重构已有包结构。

### 4.2 依赖注入

统一使用构造器注入。

禁止使用字段级 @Autowired。

已有 Lombok 约定时，可以使用：

@RequiredArgsConstructor

依赖字段使用 private final。

### 4.3 API 设计

- 保持已有 API 路径和响应格式。
- 使用 Jakarta Validation 进行参数校验。
- 优先使用项目已有的统一异常处理机制。
- 不直接暴露 JPA Entity，除非项目明确采用这种设计。
- 不将敏感信息写入日志。

### 4.4 事务管理

- 根据业务需要使用 @Transactional。
- 不随意扩大事务范围。
- 注意 Spring 代理与内部方法调用问题。
- 修改数据时保持原有事务语义。

### 4.5 Lombok

- 使用项目已有的 Lombok 风格。
- 优先使用 @Getter、@Setter、@RequiredArgsConstructor。
- 避免在 JPA Entity 上直接使用 @Data。
- 不无故引入 @SneakyThrows。

## 5. 数据库规范

- 使用项目现有的数据访问方式。
- 不随意修改表结构。
- 需要修改数据库结构时，优先使用 Flyway。
- 不修改已经执行过的历史迁移脚本。
- 数据库字段映射遵循现有命名约定。

## 6. 代码修改原则

### 6.1 最小改动

执行任何任务时：

1. 先阅读相关代码。
2. 理解当前实现及调用关系。
3. 只修改完成任务必需的文件。
4. 不进行无关重构。
5. 不修改无关接口。
6. 不随意引入新依赖。
7. 不删除现有测试。

### 6.2 兼容性

除非任务明确要求：

- 不改变已有 API 行为。
- 不改变数据库字段含义。
- 不改变公共方法签名。
- 不改变已有异常语义。
- 不破坏现有数据兼容性。

### 6.3 新增代码

- 遵循已有包结构。
- 优先复用已有类和工具。
- 不创建功能重复的工具类。
- 不添加未使用的代码。
- 对必要的业务逻辑添加测试。

## 7. Java 格式化范围与验证

### 7.1 作用范围

Java 格式规范适用于服务端五个 Maven 子模块的生产和测试源码，不适用于前端、SQL 或 Markdown。

- 日常任务只对受影响模块执行格式化，检查实际差异，避免夹带无关格式变更。
- 当前父 POM 同时包含 `src/main/java/**/*.java`、`src/test/java/**/*.java` 及带 `**/` 前缀的递归规则。
- 因此父工程自身执行 Spotless 也会扫描子模块；限制 Maven reactor 范围与限制文件匹配范围是两回事。
- 首次建立全项目格式基线时，单独处理和提交，不与业务变更混在一起。

### 7.2 Spotless 执行

以下命令默认在仓库根目录执行，使用 JDK 21。若存在 Maven Wrapper，优先使用 Wrapper；当前工程未提供 Wrapper，示例使用 `mvn`。
本机 Maven 不在 PATH 时，使用本机已配置的 Maven 可执行文件，不把机器专用路径写入仓库。

仅格式化业务模块并检查：

```shell
mvn -f backend/pom.xml -pl app spotless:apply
mvn -f backend/pom.xml -pl app spotless:check
```

多个受影响模块用逗号分隔，例如业务实现和启动模块集成测试同时有改动：

```shell
mvn -f backend/pom.xml -pl app,bootstart spotless:apply
mvn -f backend/pom.xml -pl app,bootstart spotless:check
```

`-pl` 可选值为 `common`、`document-parser`、`app`、`agent`、`bootstart`。也可以进入对应子模块目录执行 `mvn spotless:apply`、`mvn spotless:check`。
日常格式化命令不加 `-am`，避免把依赖模块及父工程也纳入执行；Spotless 直接处理源码，无需先构建依赖模块。

只有任务明确包含全服务端格式基线调整时，才执行全量格式化：

```shell
mvn -f backend/pom.xml spotless:apply
mvn -f backend/pom.xml spotless:check
```

父 POM 的递归规则会覆盖所有子模块，即使加 `-N` 只执行父工程，也不能用来限制到单个子模块。
`apply` 会修改匹配的 Java 文件；`check` 只校验格式，不自动修复。模块级 `apply` 也会处理该模块内未修改的 Java 文件，执行后必须检查差异。

`spotless:check` 已绑定 `verify` 阶段，完整验证使用 `mvn -f backend/pom.xml verify`。
`validate`、`compile`、`test`、`package` 均不会推进到 `verify`，不能把这些命令成功当作格式检查通过。
完整 `verify` 会执行父工程及各子模块检查；在现有格式基线未统一时，可能因历史代码格式失败，应如实说明，不为通过检查而擅自全量格式化或跳过检查。

### 7.3 跨模块修改

如果任务需要修改其他 Maven 模块：

- 允许按业务需要修改其他模块。
- 遵循目标模块自身的编码规范。
- 服务端各模块遵循统一父 POM 的格式规则，前端仍遵循自身规范。
- 使用 `-pl` 显式列出受影响模块；非格式基线任务不执行父工程全量 `spotless:apply`。
- 不进行无关的全项目格式化。

### 7.4 验证

执行格式化后：

1. 对受影响模块执行 `spotless:check`。
2. 编译受影响模块及必要依赖，例如 `mvn -f backend/pom.xml -pl app -am compile`。
3. 运行相关测试；涉及业务集成时通常需选择 `bootstart` 并带 `-am`，例如 `mvn -f backend/pom.xml -pl bootstart -am test`。Testcontainers 集成测试需要可用的 Docker 环境。
4. 前端有改动时，在 `frontend` 目录执行 `pnpm test` 和 `pnpm build`；需要安装依赖时使用 `pnpm install --frozen-lockfile`。
5. 检查 `git diff` 和 `git diff --check`，确认没有无关格式修改，也没有覆盖用户未提交的内容。

格式化检查不能替代编译和测试。仅修改文档时检查内容与差异，无需运行 Java 格式化和业务测试；仅修改构建配置时执行与该配置相关的验证。

## 8. Git 修改规范

- 不提交 target/ 等构建产物。
- 不进行与当前任务无关的全项目格式化。
- 不覆盖用户尚未提交的修改。
- 不执行破坏性的 Git 操作。
- 修改完成后检查 git diff。
- 首次引入 Spotless 时，
  将格式基线调整与业务功能变更分开提交。

## 9. Codex 工作流程

处理开发任务时遵循：

1. 分析需求。
2. 阅读相关类和配置。
3. 确定最小修改范围。
4. 功能实现前先添加或更新相关测试，确认测试覆盖需要变更的行为。
5. 实现功能；涉及菜单功能时同步更新对应设计和实现文档。
6. Java 源码有改动时，按第 7 节对受影响模块执行 Spotless 格式化；纯文档或前端任务不执行 Java 格式化。
7. 检查格式、编译及相关测试。
8. 检查 Git diff。
9. 输出修改说明和验证结果。

## 10. 任务完成输出

完成任务后，简要说明：

- 修改了哪些文件。
- 实现了什么功能。
- 是否执行了 Spotless。
- 编译和测试是否通过。
- 是否存在尚未解决的问题。

不得虚构未执行的测试或检查结果。
