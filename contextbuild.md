# Context Build：模型调用前的上下文构建

## 1. 目标与边界

Context Build 在**每次实际模型调用前**，把当前任务所需的信息构造成一个可校验、可追溯且符合模型输入预算的请求。同一次 Agent Run 中，工具返回后的下一次模型调用也要重新构建；重试则按明确的快照策略复用或重建。

它回答三个问题：

1. 这次调用必须看到什么？
2. 预算有限时，还应选择什么、舍弃什么？
3. 最终发送给模型的内容是否完整、合法、可回放？

输入是 Runtime 的一致性快照，以及 Event、Memory、Knowledge、Tool 等来源提供的候选内容。构建结果是 `Ready(ContextPackage)`、`CompactRequired(CompactRequest)` 或明确失败；`ContextPackage` 包含模型请求、Token 明细、来源引用和选择记录。

Context Build 只负责读取、选择和装配当前模型视图。事件存储与运行状态由 Runtime 管理；长期记忆的写入与整理见 [memory.md](./memory.md)；超预算时的压缩策略和提交过程见 [compact.md](./compact.md)。构建器可返回 `CompactRequired`，由 Runtime 的 `compact_context` 节点执行压缩；构建器自身不调用压缩模型或提交摘要。

```text
Runtime snapshot + Context sources
              ↓
       Context Builder
              ↓
Ready(ContextPackage) / CompactRequired / Failure
              ↓
         Model call
```

系统保存过的信息不等于本次模型可见的信息。构建结果是针对 `turn_id`、`generation`、面试阶段和目标模型的一次投影，不是新的事实来源。

## 2. 一次构建的流程

| 步骤          | 输入                    | 处理与输出                                                                          |
| ------------- | ----------------------- | ----------------------------------------------------------------------------------- |
| 1. 固定快照   | `ContextBuildRequest` | 读取指定`generation` 的 Event/State 视图，确定本次调用的当前输入与任务阶段。      |
| 2. 收集候选   | 快照、来源配置          | 各 Provider 返回带来源引用的`ContextItem`；独立来源可以并行读取，但共用截止时间。 |
| 3. 准入       | 候选项                  | 按租户、用户、面试、会话、分支与权限过滤；标记信任等级和过期状态。                  |
| 4. 规范化     | 已准入项                | 去重、建立原子组和依赖边，计算或估计 Token 成本。                                   |
| 5. 保护       | 任务状态与依赖图        | 确定必选项及其传递依赖，检查它们是否能够放入输入预算。                              |
| 6. 选择       | 剩余预算与弹性项        | 按阶段、相关性、证据依赖、来源质量、时间和成本确定性地选取。                        |
| 7. 处理超额   | 超预算投影              | 先移除冗余和低优先级弹性项；仍需压缩时返回 `CompactRequired`，由 Runtime 处理后重新构建。 |
| 8. 装配与校验 | 选中项                  | 形成逻辑消息、工具 Schema 和输出约束；检查依赖、信任边界及完整请求预算。            |
| 9. 适配与记录 | 合法投影                | 转换成目标模型请求，记录选择原因、来源、Token 明细和内容 Hash。                     |

构建器不得直接截断最终 Prompt 字符串。任何内容移除都应发生在结构化 `ContextItem` 或原子组层，并留有原因记录。

## 3. 输入契约

```python
@dataclass(frozen=True)
class ContextBuildRequest:
    tenant_id: str
    user_id: str
    interview_id: str
    session_id: str
    run_id: str
    turn_id: str
    agent_id: str
    branch: str | None
    generation: int
    phase: InterviewPhase
    current_input: InputItem
    active_question_id: str | None
    model_profile: ModelProfile
    output_reservation: int
    deadline: datetime | None
```

`generation` 固定本次读取的事件与状态版本。`current_input` 必须显式传入，不靠“取历史最后一条消息”推断。`phase` 用于切换选择权重，例如规划时优先 JD 和简历，评分时优先 Rubric 与原始证据。

`ModelProfile` 至少提供模型名、Context Window、Tokenizer 或估算方式、Provider 安全余量，以及 System Role、Tool Call、输出 Schema 等能力。模型能力会影响预算和最后的请求转换，不应改变业务证据的优先级。

## 4. 候选内容与来源

Provider 只收集候选内容，不自行决定最终消息顺序或预算分配。建议首批来源如下：

| 来源                  | 典型候选                                          | 构建时关注点                                            |
| --------------------- | ------------------------------------------------- | ------------------------------------------------------- |
| 固定指令              | 平台策略、Agent 角色、运行约束、输出 Schema       | 必需且来源可信。                                        |
| 面试状态              | Interview Plan、能力覆盖、当前阶段、Working State | 必须与快照版本一致。                                    |
| 当前 Question Episode | 问题、回答、追问、当前 Rubric、证据引用           | 保持当前任务依赖完整。                                  |
| 历史事件              | 原始问答、工具交互、Rolling Summary               | 按 Episode 与 Tool Interaction 组织。                   |
| Memory                | Core、Deferred 索引/正文、Episodic Recall         | 由 Memory 系统提供；是否进入本次上下文由 Builder 决定。 |
| Knowledge             | JD、简历、题库、企业文档                          | 保留文档 ID、版本、片段位置。                           |
| Tool                  | 可用工具 Schema、未完成调用及结果                 | Schema 与调用状态要匹配。                               |

每个 Provider 应有稳定的 `source_id`、读取超时和结果上限。关键来源失败时阻止构建；可选检索来源失败时记录降级原因。Provider 返回顺序不代表优先级。

统一的内部表示：

```python
@dataclass(frozen=True)
class ContextItem:
    id: str
    source_id: str
    kind: ContextKind
    content: Any
    scope: ContextScope
    trust_level: TrustLevel
    token_count: int
    provenance: tuple[SourceRef, ...]
    atomic_group_id: str | None = None
    dependency_ids: tuple[str, ...] = ()
    dedup_key: str | None = None
    created_at: datetime | None = None
    expires_at: datetime | None = None
```

`SourceRef` 至少能定位到 `source_type`、`source_id`，并按内容类型记录 `event_id`、`question_id`、`artifact_id`、版本或内容 Hash。`ContextItem` 是中间表示；Provider 格式的消息只在装配后生成。

## 5. 准入、去重与依赖

### Scope 与信任边界

先按 `tenant_id → user_id → interview_id → session_id → branch/agent_id` 限制可见范围，再执行检索和选择。跨范围候选项应直接拒绝并记录 `OUT_OF_SCOPE`，不能靠低优先级处理。明确的 Event、Question、Artifact ID 应优先于模糊匹配。

平台指令、运行状态、业务资料、候选人回答、外部文档和工具结果具有不同信任等级。候选人回答、简历、检索片段、Tool Result 与 Summary 都是**数据**；装配时应保留清晰边界，不能让其中的文字变成系统指令或改变工具权限。

### 去重

优先用 Event/Artifact/Document Chunk ID 和内容 Hash 去重。同一事实同时出现在 Raw Event、Recall 和 Summary 时，优先保留可核对的原始证据；若 Raw Event 已展开，应避免重复附带覆盖它的 Summary 片段。保留项需合并来源引用，不能丢失归因。

### 原子组与依赖

- Tool Call、授权状态、Tool Result 和模型继续执行所需的 Provider opaque state 应作为一个依赖集合处理，不得产生孤立结果。
- 当前 Question Episode 的问题、回答、追问、Rubric 版本和评分所需 Evidence 应作为任务依赖处理；评分不能脱离原文证据。
- 原子组可以整体保留、整体替换为有效压缩表示或整体排除。当前开放 Episode 和未完成 Tool Interaction 不得被普通历史摘要替换。

依赖关系用 `dependency_ids` 显式表达。选中一个评分结论或工具结果时，Builder 必须把其必需依赖加入候选闭包；闭包无法满足时，该项不能进入请求。

## 6. 保护与选择规则

保护等级是**本次调用的决策**，不是来源的永久属性。

| 等级        | 内容                                                                                      | 处理规则                                           |
| ----------- | ----------------------------------------------------------------------------------------- | -------------------------------------------------- |
| P0 必需     | 平台/安全指令、当前输入、输出契约、当前开放 Episode、未完成工具交互及必要 opaque state    | 必须完整；放不下则构建失败。                       |
| P1 任务依赖 | 当前能力目标、相关 Interview Plan、当前 Rubric、评分所需原始 Evidence、必要 Working State | 默认保留；若超额，只能走明确的任务分块或业务降级。 |
| P2 弹性     | 相关 JD/简历、Memory、Recall、Knowledge、旧 Episode 摘要                                  | 按本次任务的价值与成本选择。                       |
| P3 优先缩减 | 重复检索、旧工具大结果、可恢复日志、已被摘要覆盖的 Raw Event                              | 先去重、缩减或排除。                               |

先把 P0/P1 及其依赖放入预算，再分配弹性空间。弹性项采用确定性排序：阶段相关性、明确 ID 命中、证据依赖、来源质量、时效性、重复度、Token 成本；相同得分用稳定 ID 打破平局。第一版不需要 LLM Selector。

每个候选项都记录 `selected`、`reason`、`token_count`、保护等级和来源。未选原因至少区分 `OUT_OF_SCOPE`、`PERMISSION_DENIED`、`EXPIRED`、`DUPLICATE`、`SUPERSEDED`、`INVALID_DEPENDENCY`、`LOW_RELEVANCE` 和 `BUDGET_EXHAUSTED`。

### 按面试阶段选取

| 阶段             | 必须加载                                                   | 优先竞争弹性预算                                      | 通常不加载                       |
| ---------------- | ---------------------------------------------------------- | ----------------------------------------------------- | -------------------------------- |
| `PLANNING`     | 当前需求、岗位能力要求、计划输出 Schema                    | JD、简历中与能力模型对应的片段、题库候选              | 旧工具日志、无关历史问答         |
| `INTERVIEWING` | 当前问题、候选人最新回答、追问目标、能力覆盖状态           | 相关简历片段、上一题结论、与当前回答有关的历史证据    | 全量 JD、所有已关闭 Episode 原文 |
| `EVALUATING`   | 当前 Episode 原文、Rubric 版本、Evidence Span、评分 Schema | 同能力的历史回答、必要的 JD 要求                      | 无关题库、旧搜索结果正文         |
| `REPORTING`    | 报告 Schema、各能力评分、每项结论的原始 Evidence Ref       | 跨题矛盾与亮点、岗位要求、已关闭 Episode 的结构化结论 | 无关工具日志、未被引用的检索片段 |

表中的“必须加载”仍须以具体任务和依赖图为准。例如 `INTERVIEWING` 如果正在核对候选人此前的说法，就要按 `question_id` 取回对应原始回答，而不是只读上一题摘要。

### V0 选择顺序

```text
1. 拒绝越权、过期或权限不足的候选项；记录拒绝原因。
2. 按稳定 ID/Hash 去重；把 Tool Interaction 和 Question Episode 建成原子组。
3. 根据 phase 和当前任务标记 P0/P1，并求出必需依赖闭包。
4. 若必需闭包超预算，返回 PROTECTED_CONTEXT_OVERFLOW。
5. 构造弹性候选组；包含缺失依赖的组直接拒绝。
6. 按“明确 ID/证据依赖 → 阶段相关性 → 来源质量 → 时效性 → Token 成本 → 稳定 ID”排序。
7. 逐组尝试加入；放不下就记录 BUDGET_EXHAUSTED，继续考察后续较小的组。
8. 重新计算装配后的完整请求 Token；超额则移出末位弹性组并重算。
```

这里的“组”是预算和选择的最小单位。一个组的成本包含其尚未选中的必需依赖，不能先选结论、再因为预算不足丢掉证据。V0 的排序规则应配置化并固定版本，以便同一快照能够重放相同结果。

## 7. Token 预算

```text
input_budget = model_context_window
             - output_reservation
             - provider_safety_margin

fixed_tokens + protected_tokens + selected_elastic_tokens
             + provider_envelope_tokens <= input_budget
```

计数范围包含 System 指令、Memory、Skill、Tool Schema、历史投影、检索结果、当前 Episode、当前输入、输出约束和 Provider 消息包装。优先使用目标 Provider 或模型 Tokenizer 的准确计数；只有估算可用时，应增加安全余量。

预算按面试阶段调整优先级与上限，不为所有来源固定分配百分比。Builder 输出 `tokens_by_source`、`tokens_by_kind`、`provider_envelope_tokens`、`reserved_output_tokens` 和剩余预算，方便解释选择结果。

若 P0/P1 与其必需依赖本身超过预算，返回 `PROTECTED_CONTEXT_OVERFLOW`；不能通过通用截断损坏当前问题或评分证据。

### 一次评分调用的预算示例

假设模型窗口为 16,000 Token，预留输出 4,000、安全余量 1,000，则输入预算为 11,000。固定指令与 Schema 占 1,600，当前 Episode、Rubric 和证据等受保护内容占 4,200，Provider 包装估算 200，弹性内容最多可用 5,000。

当前任务是给第 4 题评分；第 4 题的必需证据已在受保护内容中。候选人又提到了第 2 题的吞吐量，Builder 尝试取回原文辅助核对。候选项如下，Token 数只是演示：

| 候选项                          | Token | 决策 | 原因                                                   |
| ------------------------------- | ----: | ---- | ------------------------------------------------------ |
| 第 2 题原始回答与 Evidence Span | 1,700 | 选入 | 当前话题命中`question_id=q2`，先按 ID 恢复原文。     |
| 第 1、3 题的有效 Summary        |   650 | 选入 | 维持面试进度，且不重复覆盖第 2 题原文。                |
| JD 中的吞吐量要求片段           | 1,100 | 选入 | 与当前 Rubric 判断直接相关。                           |
| 企业知识检索片段                | 2,600 | 排除 | 前三项占 3,450，剩余 1,550；记录`BUDGET_EXHAUSTED`。 |
| 旧工具完整输出                  | 3,000 | 排除 | 可由 Artifact ID 恢复，且与本次评分无关。              |

最终估算输入为 `1,600 + 4,200 + 200 + 3,450 = 9,450` Token，剩余 1,550。即使还有空位，也不会为了填满窗口而放入无关内容。若第 2 题原文与 Summary 覆盖范围重叠，Builder 只保留 Summary 中未被展开的第 1、3 题内容。

如果第 2 题原文实际上是第 4 题评分的**必需证据**，它就应升级为 P1，先计入受保护预算；此时超预算不能把它作为弹性项丢弃。这个区分由当前任务的 Evidence Dependency 决定。

## 8. 与 Compact、Recall 的接口

Context Build 只规定何时请求压缩，以及压缩后的内容如何重新进入构建流程。

超预算时，先执行确定性去重、移除低价值弹性项和可恢复的大型结果。仍需压缩历史时，Builder 返回 `CompactRequired`，包含 `expected_generation`、目标预算、受保护 Item ID 与候选原子组 ID。Runtime 的 `compact_context` 节点调用 Compact；只有成功提交新 Generation 后，Runtime 才重新调用 Builder 收集、选择和校验。压缩失败时不得隐藏未经有效摘要覆盖的原始事件；连续压缩仍未缓解压力应受次数/收益阈值限制并返回可诊断失败。压缩细节见 [compact.md](./compact.md)，节点路由见 [runtime.md](./runtime.md)。

需要过去的精确事实时，Builder 可向 Recall 提供 Scope、明确 ID 和任务查询。Recall 返回的内容仍是候选 `ContextItem`，仍需经历准入、去重、依赖和预算选择。检索无结果、超时、后端错误、权限过滤与预算淘汰应分别记录，不能统称“没有历史”。

## 9. 装配、校验与输出

逻辑装配顺序为：固定指令与角色 → 当前计划/状态 → 必需 Rubric 与证据 → 已选历史/Memory/Knowledge → 当前 Episode → 当前输入 → 输出 Schema 与工具定义。Provider Adapter 可按目标模型的消息角色和缓存规则调整物理格式，但必须保留信任边界与依赖关系。

```python
@dataclass(frozen=True)
class ContextPackage:
    projection_id: str
    generation: int
    instructions: tuple[ContextItem, ...]
    messages: tuple[ContextItem, ...]
    tools: tuple[ToolSchema, ...]
    token_breakdown: TokenBreakdown
    selection_records: tuple[SelectionRecord, ...]
    provenance: tuple[SourceRef, ...]
    provider_request: Any
    content_hash: str
```

发送模型前至少校验：

- 当前输入、当前 Episode 与所有 P0/P1 依赖完整；
- Rubric 版本与 Evidence Ref 可解析；
- Tool Call/Result 配对，Provider opaque state 未损坏；
- 无跨 Scope 内容、无未隔离的不可信指令、无重复的 Summary 与已覆盖 Raw Event；
- 完整 Provider 请求处于预算内，输出预算仍被保留，Schema 合法。

每次模型调用记录 `ContextProjection`：`projection_id`、`run_id`、`turn_id`、`generation`、模型、选中/拒绝 Item ID、来源 Event ID、压缩事件 ID、Token 明细、选择原因和 `content_hash`。敏感正文可只保存安全引用与 Hash。这个记录用于重放并定位“来源没有提供、Builder 没有选中、预算淘汰或模型推理出错”。

对应上面的示例，记录可以长这样（省略正文）：

```json
{
  "projection_id": "proj-42",
  "turn_id": "turn-4-evaluate",
  "generation": 7,
  "phase": "EVALUATING",
  "input_budget": 11000,
  "input_tokens": 9450,
  "selected": [
    {"item_id": "q4-episode", "reason": "PROTECTED_CURRENT_EPISODE"},
    {"item_id": "q2-answer", "reason": "EXACT_QUESTION_MATCH"},
    {"item_id": "jd-throughput", "reason": "RUBRIC_RELEVANT"}
  ],
  "rejected": [
    {"item_id": "enterprise-doc", "reason": "BUDGET_EXHAUSTED"},
    {"item_id": "old-tool-body", "reason": "LOW_RELEVANCE"}
  ],
  "content_hash": "..."
}
```

实际记录还应包含完整的 Source Ref、全部选中项和分项 Token 统计。这个示例强调：选择结果既能解释给调试者，也能用于同一快照的重放校验。

## 10. 一致性与失败处理

构建期间若出现属于本次调用的当前输入或 Tool Result，必须基于新快照重建。无关的后台 Memory 更新可以留到下一次调用。同一次请求的重试默认复用相同 Projection；只有策略明确要求重新检索或输入已变更时才重建。旧 `generation` 的构建结果不能覆盖新投影。

关键来源（策略、当前 Episode、Rubric、必需 Evidence）读取失败时停止调用。可选 Memory/Knowledge/Recall 超时可降级，但要记录原因。Tokenizer 不可用时使用校准估算和更大安全余量。Provider 报溢出时，允许在有次数上限的前提下提高余量并重建。

首版验收重点是：每次模型调用都有可重放的 Projection；当前 Episode、Rubric、Evidence 和工具依赖无缺失；任何进入请求的内容都有来源与选择原因；最终 Provider 请求不超过预算。后续用这些记录评估相关内容召回率、无关 Token 比例、证据完整性、构建延迟与溢出恢复率。
