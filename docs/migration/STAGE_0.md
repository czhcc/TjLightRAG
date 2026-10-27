# 阶段 0：移植契约与对照基线

## 状态与范围

2026-10-26，在 develop 分支执行。阶段 0 交付源码基线、许可、样本、差异规则和可执行的比较框架；不在此阶段实现生产 RAG 或升级数据库。以下“已执行”仅指参考程序与基线检查，不能解释为 Java 算法移植已经完成。

| 任务 | 交付物 | 状态 |
| --- | --- | --- |
| 固定上游版本与许可 | THIRD_PARTY_NOTICES.md、third_party/LightRAG/LICENSE、provenance.json | 已完成；许可原文与上游一致 |
| 核心算法来源与差异台账 | SOURCE_MAP.md | 已完成；各能力关联样本、比较规则及后续接入阶段 |
| 中英文固定输入和模型响应 | corpus.json 的 input 字段 | 已完成；原文、token、模型响应、图、固定向量及参数均可审查 |
| 运行真实上游函数生成 golden 数据 | generate-lightrag-baseline.py | 已执行；未用重新编写的 Python 算法冒充参考实现 |
| 可重复性与漂移检查 | 生成器 --check、源码及语料 SHA-256 | 已执行；严格比较完整生成结果，不改写现有语料 |
| Java 对照测试骨架 | LightRagGoldenCorpusTest、GoldenAssertions、GoldenAssertionsTest | 已执行；验证语料不变量、来源哈希和比较器；生产候选适配器尚待各阶段接入 |
| 前端导航边界测试 | Navigation.test.tsx | 已执行；测试会话恢复、语言切换、未登录与注销失败边界；未提前加入知识库菜单 |
| 后续生命周期契约 | acceptance-scenarios.json | 已定义输入、预期和来源；是本项目手写目标契约，不是已执行的上游结果 |

## 文件与格式

固定上游提交：`aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a`。

- `backend/bootstart/src/test/resources/lightrag-baseline/corpus.json`：每条记录有唯一 id、family、source、input、expected。默认比较数组顺序和完整字段；不允许只比较计数。
- 同目录 `provenance.json`：上游 SHA、选定源码/许可/uv.lock 的 SHA-256、语料整体 SHA-256 和案例数量。固定时间戳为 `1700000000`；没有当前时间、运行路径或随机 UUID。
- `docs/scripts/generate-lightrag-baseline.py`：从固定检出直接导入函数，检查源文件无修改。内存图/存储、精确 cosine、模型响应是测试替身；不替换被验证的上游算法函数。
- `docs/migration/acceptance-scenarios.json`：文档删除、共享块、恢复、索引代、bypass 和缓存隔离的后续验收输入/预期。明确区分上游语义和本平台新增事务/隔离设计。

## 语料覆盖

实际执行 chunking、实体/关系记录解析、关键词解析、来源合并、描述去重、VECTOR 配额、引用编号、token 预算、local/global/hybrid/mix 搜索、naive 块召回、三路块合并、上下文/提示词渲染、摘要、固定重排、缓存参数摘要、真实节点/边合并与重放，以及带一次 gleaning 的抽取编排。

- 中文/英文/emoji 的分块样本保存完整 token 序列、块顺序及原文区间。
- 模型响应均为手写确定性数据；抽取编排实际消费初始响应和补充响应，保存调用提示词及最终实体/关系。摘要和重排也消费固定响应，不是实际模型质量评估。
- 检索样本保存节点、边、三类向量、关键词 embedding、topK=2 和实际调用轨迹；可分辨 hybrid 没有 mix 的直接块召回。
- 内存向量替身采用精确 cosine，排除 HNSW 近似误差；Java 测试独立复算 naive 样本的向量顺序。相同分数使用 ID 排序是测试替身规则，不能宣称上游 PostgreSQL 已保证同分顺序。
- 节点重放保持结果不变；边贡献按 c1、c1、c2 重放，权重预期是 1、1、2。
- 引用测试包含中文来源重复、unknown_source 和空结果；引用优先级按来源出现频率，再按首次出现顺序。
- 错误用例包括非法重叠、坏抽取行、自环、非对象关键词和向量服务异常；异常不能转换成“检索没有结果”。

## tokenizer 限制

**阶段 2 更新：** 已取得 Maven 发布的原始 cl100k_base 词表，SHA-256 与官方一致，并新增独立真实 BPE 对照；见 [STAGE_2.md](STAGE_2.md)。以下记录是阶段 0 当时的限制，原受控语料不被覆盖。

本次环境访问 `openaipublic.blob.core.windows.net` 下载 cl100k_base 词表返回代理 403。未关闭 TLS 或改写词表校验值。语料使用上游允许注入的 `Tokenizer` 接口，测试 tokenizer 标识为 `unicode-codepoint-test-v1`：一个 Unicode 码点为一个受控 token，类似上游测试中的 DummyTokenizer。

这是一种显式测试模型，不是对生产 token 数的估算策略，也不代表 cl100k_base/BPE 已验证。阶段 2 实现前须选择实际模型 tokenizer，取得可信词表并保留校验和，新增真实 BPE 语料组；不能覆盖或重命名这组受控语料来掩盖差异。Java 字符串使用 UTF-16，原文位置核对需转换码点偏移，emoji 用例已覆盖。

## Java 接入规则

`GoldenAssertions.assertEquivalent(expected, actual, tolerance)` 是测试作用域的复用入口。后续为生产算法增加适配器：读取一条 input，调用实际 Java 服务，把输出映射为稳定 JSON，再比较该条 expected；禁止把 expected 直接作为 actual，也禁止引入读取 golden 后返回结果的“生产实现”。

比较器保持数组顺序、对象字段集合、空值和错误严格一致；对象字段书写顺序无关。整数严格比较；浮点使用调用方显式传入的绝对误差，默认建议 `1e-8`。数据库 ANN 在阶段 1/4 单独评测召回，不能拿这个误差替代 ANN 召回门槛。差异台账中明确允许变化的字段由适配器规范化，不全局删除时间/来源等字段。

当前 Java 测试只证明参考语料、测试不变量和比较框架有效；即使这些测试全绿，也不能表示尚未接入的 Java 抽取/图谱/检索服务通过了对照。后续实现的算法测试必须与本基线测试分别报告。手写生命周期契约同样必须在真实服务/数据库上实现后才能报告通过。

## 重现命令

Python 只用于开发对照，不加入 Maven 应用或 Docker 运行镜像。需要 Python 3.12、uv、Git，源码目录位于应用检出之外。所有依赖由固定上游 uv.lock 冻结解析；不安装全部 API/OCR/模型服务依赖。

```sh
git clone https://github.com/HKUDS/LightRAG.git /tmp/LightRAG-reference
git -C /tmp/LightRAG-reference checkout --detach aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a
UV_CACHE_DIR=/workspace/.cache/uv \
UV_PROJECT_ENVIRONMENT=/workspace/tools/lightrag-reference-venv \
UV_SYSTEM_CERTS=true \
uv sync --project /tmp/LightRAG-reference --frozen --no-dev

cd /workspace/TjLightRAG
/workspace/tools/lightrag-reference-venv/bin/python \
  docs/scripts/generate-lightrag-baseline.py --upstream /tmp/LightRAG-reference --check
```

若目录已存在，核对 SHA 与工作区再使用，不覆盖其他检出。首次建立或明确更新基准时省略 `--check`；该操作会改写语料，必须审查输入、源码哈希、语料 diff 和差异规则。升级上游时还需修改脚本固定 SHA 与台账，不能直接从 main 生成基准。

```sh
source docs/scripts/cloud-env.sh
cd backend
mvn -B -pl bootstart -am \
  -Dtest=LightRagGoldenCorpusTest,GoldenAssertionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
cd ../frontend
pnpm test
pnpm build
```

完整后端回归使用 `mvn verify`，需要先按现有说明启动本地 PostgreSQL。上面的基线专用命令不需要数据库或模型凭据；应核对实际测试数量，不能把零测试运行当作成功。

## 本次执行结果

| 检查 | 结果 | 证明范围 |
| --- | --- | --- |
| 固定上游参考程序 | 50 个 G 案例生成成功；独立进程 --check 完整重放一致 | 直接执行上游选定函数，18 个案例族；不是上游全量 pytest 套件 |
| 许可与来源 | 许可字节与固定上游一致；源码/uv.lock/语料哈希保存 | 支持追溯与检测基准漂移 |
| Java 基线检查 | LightRagGoldenCorpusTest：52 项通过；GoldenAssertionsTest：4 项通过；无跳过 | 50 个语料不变量 + 覆盖/来源检查 + 校验和检查 + 比较器变异测试，不是 56 个生产算法测试 |
| 后端既有集成回归 | AuthIntegrationTest：1 项通过；完整 mvn verify 成功 | 现有 PostgreSQL/Flyway/JPA/登录行为未被破坏 |
| 前端 | 原有 2 项 + 新增导航 3 项，全部通过；TypeScript/Vite 构建通过 | 现有会话导航边界与构建兼容性 |
| C 类生命周期契约 | 6 个场景已登记，未执行 | 不计入通过数量，待阶段 2/5/6 接入真实服务 |

本次没有运行上游全量测试、真实模型、真实 BPE 或 pgvector/ANN 测试。Maven 最终构建成功；构建过程中可选 OW2 snapshot 元数据探测返回 403，不影响已解析的稳定依赖，不需要放宽网络白名单。

## 后续边界

阶段 0 的固定样本用于行为回归，不足以证明生产检索质量。后续语料扩充需固定相关性标签和 topK，记录 Recall@K/MRR/引用指标；真实模型、pgvector/HNSW、数据库恢复、文件解析布局和 SSE 网络行为尚未验证。不得将手写生命周期预期、内存存储结果或导航测试描述为平台相关功能已交付。
