# Context Compact：四层渐进式压缩

## 1. 压缩解决什么问题

一场面试会不断产生回答、追问和工具结果。当这些历史内容挤占模型输入窗口时，Compact 将**较旧、可恢复的内容**转换为更短的表示，并让后续模型调用使用新的上下文投影。原始 Event 和评分证据仍保存在原处。

本设计参考项目内 Microsoft Agent Framework（MAF）的[可组合压缩策略](./referenceProject/agent-framework/python/packages/core/agent_framework/_compaction.py)及其[组合示例](./referenceProject/agent-framework/python/samples/02-agents/compaction/advanced.py)。MAF 提供工具结果折叠、旧工具组排除、摘要和窗口/截断等策略；下面的**四层是本项目为面试场景设计的执行顺序**，不是 MAF 自带的固定分层。L2 将摘要与工具组排除组合成一次有来源校验的 LLM 压缩。

Context Builder 计算完整请求预算、选定受保护内容并返回 `CompactRequired(CompactRequest)`；Runtime 的 `compact_context` 节点据此执行，详见 [contextbuild.md](./contextbuild.md) 和 [runtime.md](./runtime.md)。本文只定义压缩候选如何缩短、何时停止、如何验证和提交压缩结果。

```text
旧历史与工具结果
  → L1 工具结果瘦身
  → L2 LLM 工具语义压缩
  → L3 已关闭 Episode 摘要
  → L4 受限窗口兜底
  → 新的 Context Projection
```

每层执行后重新计数；达到目标就停止，不必跑完四层。各层只能处理明确的候选原子组，不能修改受保护内容。

## 2. 压缩输入和共同约束

```python
@dataclass(frozen=True)
class CompactRequest:
    session_id: str
    expected_generation: int
    input_budget: int
    target_tokens: int
    current_projection_tokens: int
    protected_item_ids: tuple[str, ...]
    candidate_group_ids: tuple[str, ...]
    reason: str  # budget_pressure | provider_overflow
```

`input_budget` 已扣除模型输出预留和 Provider 安全余量；`target_tokens` 是本轮希望达到的输入规模，必须小于或等于 `input_budget`。Compact 只处理 Builder 提供的候选，不能自行扩大 Scope 或把未授权资料读进摘要模型。

压缩以原子组为单位：

- **Tool Interaction**：assistant tool call、全部 sibling result、授权状态以及 Provider 要求的 opaque state。不能留下孤立的 call/result。
- **Question Episode**：原题、候选人回答、追问与回答、Rubric 版本、评分、Evidence Span 和来源 Event ID。只有 `CLOSED` Episode 可进入 L3。
- **普通对话组**：一段能够独立理解的用户输入及相应模型回复。不能从一段未完成交互中间切断。

以下内容在所有层中受保护：当前输入、当前开放 Episode、未完成 Tool Interaction、当前评分所需 Rubric 与原始 Evidence、当前输出契约和 Builder 标记的其他 P0/P1 依赖。最近历史是否保留由依赖与 Token Tail 决定；“最近四组”只能作为可配置的辅助下限，不能覆盖业务保护规则。

压缩结果必须能通过 `source_event_ids`、`artifact_id` 或 `question_id` 找回原文。派生摘要只是模型可见的历史提示，评分不能只依赖摘要。

## 3. 触发、顺序与停止

Context Builder 在每次实际模型调用前计算完整请求 Token。初始配置可在达到输入预算的 75% 时主动尝试压缩，目标降至 60%；Provider 返回溢出时重新测量并直接执行必要层级，目标仍须低于预算。阈值只是起始配置，应按模型 Tokenizer 和实际溢出记录校准。

```text
used < trigger_tokens                 → 不压缩
used ≥ trigger_tokens                 → L1 → 计数 → L2 → 计数 → L3 → 计数
仍超目标或收到 Provider Overflow        → L4 → 计数
used ≤ target_tokens                  → 停止并验证
protected_tokens > input_budget       → PROTECTED_CONTEXT_OVERFLOW
四层后仍 > input_budget               → COMPACTION_INSUFFICIENT
```

`used` 指**完整模型输入请求**，包括固定指令、工具 Schema、Memory、检索片段和 Provider 包装；压缩层只能减少它有权处理的历史部分。每层要返回 `tokens_before`、`tokens_after`、改变的组 ID 和原因。若一层没有可处理对象或没有节省 Token，继续下一层，不重复调用该层。

## 4. L1：工具结果瘦身

L1 对**已完成且不再需要原文**的旧 Tool Interaction 生成短的结构化替代表示。它对应 MAF `ToolResultCompactionStrategy` 的“用简短结果替换旧调用组”思路，但按工具类型保留可复查的字段。

| 工具结果     | 保留在投影中的内容                       | 原文位置                            |
| ------------ | ---------------------------------------- | ----------------------------------- |
| Search       | 标题、URL、命中片段、Result ID           | `source_event_id` / 文档 ID       |
| File Read    | 路径、行范围、相关片段、内容 Hash        | `artifact_id`                     |
| Command      | 命令、退出码、关键 stdout/stderr、错误码 | `artifact_id`                     |
| Database     | 查询 Hash、列名、行数、相关记录 ID       | `source_event_id` / 结果 Artifact |
| Media/Binary | 类型、元数据、可访问引用                 | `artifact_id`                     |

例如 3,200 Token 的旧搜索结果可以替换为：

```json
{
  "tool": "search_resume",
  "call_id": "call-18",
  "result": "候选人简历中有支付链路优化经历；相关段落见 resume:chunk-7",
  "source_event_id": "event-81",
  "artifact_id": "artifact-18",
  "omitted": "full_search_results"
}
```

替代物须记录它覆盖的完整 Tool Interaction ID。当前问题正在依赖的结果、未完成调用和评分证据不能进入 L1。如果 Reducer 无法确认哪些字段承载任务事实，应跳过该组。

## 5. L2：LLM 工具语义压缩

L1 只按工具类型缩短单条结果；当一个大结果仍然冗长，或多个旧工具结果共同回答一个问题时，L2 调用 LLM 抽取**结论、矛盾、失败和待核查点**，生成带来源的 `ToolDigest`。校验通过后，才用 Digest 替换对应的完整 Tool Interaction 组。L2 聚焦工具发现；L3 负责跨问答的面试进度摘要，不重复总结工具原文。

### 候选组与调用时机

只选择已完成、已持久化、可按 ID 恢复且不在 Protected Region 的工具组。按 `question_id` 或同一核查任务分批，不混合不同候选人、面试或无关任务。当前评分直接引用的工具结果、未完成调用和 Provider 必需的 opaque state 保持原样。

L1 后仍超目标，且候选组达到 `min_candidate_tokens` 时才调用 LLM。L2 独立配置 `max_summary_input_tokens`、`max_output_tokens`、`min_token_saving` 和截止时间。每批输入包括 L1 记录、必要的原始结果片段、工具名、参数摘要、状态码、`call_id` 与来源 ID；按完整工具组切批，先检查摘要模型自己的预算。单组过大时按结构边界分块提取再合并。

同一批输入按 `source_hash + prompt_version + model_version` 缓存，没有新工具事件时不重复调用。候选过短、预计节省不足或时间不够时跳过 L2，并记录原因。

### LLM 的输出

模型只填写语义字段，并从输入给定的 Event ID 中选引用；覆盖范围和版本由程序计算。输出采用受约束 Schema：

```json
{
  "question_id": "q3",
  "findings": [
    {
      "claim": "简历提到支付链路限流改造",
      "source_event_ids": ["event-81"],
      "evidence_quotes": ["负责支付链路限流改造"]
    }
  ],
  "conflicts": [],
  "tool_failures": [],
  "open_checks": ["追问峰值 QPS 和限流触发条件"]
}
```

程序在外层附加 `digest_id`、`covered_group_ids`、`covered_event_ids`、`source_hash` 和模型/提示词版本。提示词要求每条结论引用原文，保留否定、数值、单位、错误和冲突，不推断候选人能力或分数。工具结果按不可信数据处理，其中的文字不能修改这些要求。

### 替换原文的条件

1. Schema 合法；所有引用 ID 来自本批输入，`evidence_quotes` 能在对应原文定位；
2. 必要的精确标识符、失败状态和矛盾没有丢失；无法验证的关键结论保留原文，不用摘要替代评分证据；
3. `ToolDigest` 加引用的 Token 少于被替换内容，且节省达到 `min_token_saving`；
4. Digest 与原工具组一次性替换，不产生孤立 Call/Result 或没有摘要的空洞。

LLM 超时、拒绝、输出无效或节省不足时，本批 L2 不提交；保留已完成的 L1 结果，继续评估 L3。原始工具事件始终留在 Event Store，`ToolDigest` 不能作为最终评分的唯一证据。

## 6. L3：已关闭 Episode 摘要

若工具历史处理后仍超目标，L3 将**较旧、已关闭且不受保护的 Question Episode**合并为结构化 Rolling Summary；相关普通对话可一并纳入。它对应 MAF `SummarizationStrategy` 的“旧消息换摘要并保留来源关系”，并加入面试所需的字段约束。

摘要至少保留：

```json
{
  "covered_question_ids": ["q1", "q2"],
  "covered_event_ids": ["event-11", "event-19"],
  "capabilities_covered": ["并发控制"],
  "candidate_claims": [
    {"claim": "负责过支付链路限流", "source_event_ids": ["event-15"]}
  ],
  "follow_up_gaps": ["尚未说明故障回滚方案"],
  "decisions": ["下一题验证容量规划"],
  "rubric_versions": {"q1": "v3"},
  "evidence_refs": ["evidence-4"],
  "lookup_hints": ["q2:吞吐量"],
  "summary_version": 1
}
```

`covered_event_ids` 必须精确反映实际输入，不能按宽泛时间段猜测。题目 ID、Evidence ID、Rubric 版本和其他精确标识符应从结构化来源机械提取，再与模型生成的文字合并。摘要中的事实主张应指向原始 Event；无法验证的主张不能写入最终摘要。

摘要输入必须按完整原子组分批，且不超过摘要模型自己的输入预算。若已有 Rolling Summary，只把它与新关闭的组作为下一版输入，并记录旧版与新版的 supersede 关系。开放 Episode、当前评分证据和未持久化内容不能纳入。

摘要生成失败、为空、Schema 不合法或来源覆盖不完整时，不提交这次 L3；原始组仍保持可见。Summary 属于低信任派生内容，装配时须标注来源边界，不能变成系统指令或权限来源。

## 7. L4：受限窗口兜底

L4 对应 MAF 的 `SlidingWindowStrategy` / `TruncationStrategy`，但面试场景不能直接“保留最近 K 组，删掉其他全部”。这里的窗口由**受保护组 + 最近 Token Tail + 有效 Summary**组成，组数仅是辅助配置。

按以下顺序缩减：

1. 从投影中隐藏已被有效 Summary **完整覆盖**的旧 Raw Event 组；
2. 排除已被有效 `ToolDigest` 覆盖、可按 ID 恢复且没有当前依赖的旧工具组；
3. 对超长日志、搜索片段、已持久化 Tool Body 做按行/段落的受限裁剪，保留来源 ID、内容 Hash、省略长度及必要 Head/Tail；
4. 每步重新计数，达到预算立即停止。

L4 不裁剪当前问题与回答、System/Security 指令、Tool Schema、Rubric、原始评分证据、未完成 Tool Interaction，也不把未被有效摘要覆盖的候选人回答直接移出投影。单个受保护对象已超预算时，应由上层选择更大模型、分块处理或报告 `PROTECTED_CONTEXT_OVERFLOW`，而不是破坏原文。

如果四层后仍放不下，返回 `COMPACTION_INSUFFICIENT`，并附上剩余 Token、受保护 Token 和无法处理的组 ID；模型调用不应继续发送一个已知超预算的请求。

## 8. 提交与投影更新

一次成功压缩生成 `CompactionEvent`，其内容至少包括：

```json
{
  "compaction_id": "compact-7",
  "expected_generation": 12,
  "new_generation": 13,
  "layers_applied": ["L1", "L3"],
  "changed_group_ids": ["tool-3", "episode-q1"],
  "covered_event_ids": ["event-11", "event-12"],
  "summary_id": "summary-4",
  "tokens_before": 9200,
  "tokens_after": 5800,
  "target_tokens": 6000,
  "source_hash": "..."
}
```

提交顺序：基于 `expected_generation` 生成候选结果 → 验证组完整性、来源覆盖与 Token → 持久化压缩事件 → 使用 CAS 提交新 Generation → 通知 Builder 从新 Generation 重建。若 Generation 冲突、取消或持久化失败，旧投影保持有效；构建器在新快照上决定是否重试。

原始 Event 不删除。某段 Raw Event 只有被有效 `CompactionEvent` 完整覆盖，且新摘要/替代表示已经提交后，才能从当前投影隐藏。多个摘要覆盖范围重叠时，只选当前有效版本，不能把旧版和新版同时发给模型。

## 9. 一次四层压缩示例

假设 Builder 给出输入预算 10,000 Token、目标 6,000 Token，当前完整请求占 9,200 Token；其中固定与受保护内容占 4,000 Token。

| 阶段 | 动作                                         | 完整请求 Token | 下一步                      |
| ---- | -------------------------------------------- | -------------: | --------------------------- |
| 初始 | 无                                           |          9,200 | 超过目标，进入 L1。         |
| L1   | 旧搜索与命令结果换成带引用的短记录           |          7,700 | 仍超过目标。                |
| L2   | LLM 合并两个旧工具组，校验 Digest 后替换原文 |          7,000 | 仍超过目标。                |
| L3   | 将两个已关闭 Episode 摘要，保留来源 ID       |          5,800 | 达到目标，停止；L4 不执行。 |

本例中 `tokens_before/after` 都是**完整请求**的计数，包含未压缩的固定部分。若这两个 Episode 仍在评分中，L3 必须跳过；如果所有可处理组耗尽后仍超过 10,000，返回失败而不是强行截断证据。

## 10. 验收规则

- 每层只能处理被允许的原子组；压缩后无孤立 Tool Call/Result，当前 Episode 与评分证据保持完整。
- 每个摘要/替代物都能定位到精确的原始 Event、Question 或 Artifact；未覆盖的原文不会被隐藏。
- 每层后重新计算完整请求 Token，达到目标即停止；最终请求必须低于输入预算。
- 同一快照和策略版本能够解释相同的候选组、层级决策与 Token 变化。
- 失败或并发冲突不会产生半提交的投影，后续模型调用只能看到旧的有效版本或新的完整版本。
