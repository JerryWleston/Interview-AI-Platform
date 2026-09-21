# Context Build Design

> 面向 Long-Horizon Interview Agent 的证据保护型、预算感知、可追溯上下文构建系统。

> Reference: Microsoft Agent Framework / Google ADK / Hermes Agent / Letta / OpenAI Agents SDK / LangGraph

## 1. Problem Definition

Context Build 负责在每一次模型调用前，根据当前面试状态、模型限制和任务目标，决定模型实际能够看到什么。

系统持久化的数据不等于模型上下文：

```text
Persisted Events
≠ Runtime State
≠ Memory
≠ Context Projection
≠ Provider Model Request
```

对于 Interview AI Platform，Context Build 不能只是拼接 Prompt，也不能退化成普通 Conversation History + RAG。它必须同时处理：

- 当前面试计划和能力覆盖；
- 当前问题、候选人回答和动态追问；
- Rubric、评分状态和 Evidence Span；
- Working/Core/Deferred/Episodic Memory；
- JD、简历、题库和企业知识；
- Tool Schema、Tool Call 和 Tool Result；
- 长会话历史、Rolling Summary 和 Recall；
- 不同模型的 Context Window、输出预算和 Provider 格式差异。

本系统的核心研究问题是：

> 在有限 Token Budget 下，为每次面试决策构建最小充分上下文，同时保证问题链、工具依赖和评分证据完整、可恢复、可解释、可评测。

---

## 2. Design Principles

### 2.1 Context Is a Projection

Context 是 Event Store、Memory、Knowledge 和 Runtime State 在某个 Turn 上的动态投影，不是持久化历史本身。

### 2.2 Evidence First

评分和最终报告必须能够定位到原始候选人回答、Rubric Version 和 Evidence Span。Summary 和向量检索结果不能作为唯一评分依据。

### 2.3 Centralized Decision

Context Provider 只负责提供候选内容，不能自行决定最终 Prompt。过滤、优先级、预算分配、压缩和装配统一由 Context Builder 完成。

### 2.4 Append-only History

Context Selection 和 Compact 只改变模型可见视图，不删除 Event Store 中已经发生的事实。

### 2.5 Deterministic First

第一版使用可解释的确定性规则完成 Scope、去重、保护、排序和预算分配。只有在 InterviewBench 积累足够数据后，才引入 LLM Selector 或 Learned Policy。

### 2.6 Build Before Every Model Call

Context Build 必须发生在每次实际 LLM Call 前，包括同一个 Agent Run 内的多轮 Tool Loop、Retry 和 Resume，而不是只在 `agent.run()` 开始时执行一次。

### 2.7 Provider Neutral

内部 Context 数据模型保持 Provider 无关，最后一步才转换为 OpenAI、Gemini、Anthropic 等模型所需格式。

---

## 3. System Boundary

```text
Agent Runtime
    │
    │ ContextBuildRequest
    ▼
Context Builder
    ├── Context Provider Registry
    ├── Context Selection Policy
    ├── Token Budget Allocator
    ├── Protected Region Resolver
    ├── Compact Coordinator
    ├── Context Validator
    └── Provider Adapter
    │
    │ ContextPackage
    ▼
Model Provider
```

各系统职责：

| 系统 | 负责 | 不负责 |
|---|---|---|
| **Runtime** | Agent Loop、State、Routing、Checkpoint、Retry、Resume | 自行拼接 Prompt |
| **Context Builder** | 收集、过滤、选择、预算、装配和校验 | 修改原始历史、提炼长期记忆 |
| **Memory** | 长期信息的存储、检索、版本和写入策略 | 决定某轮最终上下文 |
| **Compact** | 超预算时产生可恢复的压缩结果 | 常规 Context Selection |
| **Event Store** | 保存不可变运行事实 | 直接向模型输出 Provider 消息 |
| **Knowledge Base** | JD、简历、题库和企业文档检索 | 保存会话运行状态 |
| **Provider Adapter** | 转换模型请求格式 | 决定业务优先级 |

---

## 4. High-level Architecture

```text
                     ContextBuildRequest
                             │
                             ▼
┌────────────────── Context Source Providers ──────────────────┐
│ History │ Working Memory │ Interview Plan │ JD/Resume/Rubric │
│ Recall  │ Knowledge Base │ Skills         │ Tool Registry    │
└─────────────────────────────┬────────────────────────────────┘
                              ▼
                   Normalize to ContextItem
                              ▼
            Scope / Permission / Trust Filtering
                              ▼
              Deduplicate + Atomic Group Builder
                              ▼
                 Protected Region Resolver
                              ▼
                   Token Budget Allocator
                              ▼
            ┌──────────────超预算？──────────────┐
            │                                    │
           否                                    是
            │                                    ▼
            │                         Transactional Compact
            │                                    │
            │                         Persist CompactionEvent
            │                                    │
            │                            Rebuild Context
            └──────────────────┬─────────────────┘
                               ▼
                    Dependency Validator
                               ▼
                    Provider Model Adapter
                               ▼
                       ContextPackage
```

Context Builder 可以理解为一个上下文编译器：

```text
Sources
→ Context Intermediate Representation
→ Selection / Optimization
→ Validation
→ Provider-specific Request
```

---

## 5. Context Build Lifecycle

标准构建流程：

```text
1. Capture Event / State Snapshot
2. Collect Context Candidates
3. Normalize to ContextItem
4. Apply Scope and Permission Filters
5. Apply Trust Boundary
6. Deduplicate Candidates
7. Build Atomic Groups
8. Resolve Protected Region
9. Allocate Token Budget
10. Trigger Compact if Required
11. Rebuild after Successful Compact
12. Assemble ContextPackage
13. Validate Dependencies and Budget
14. Convert through Provider Adapter
15. Record Context Projection and Trace
16. Invoke Model
```

在 Tool Loop 中：

```text
Context Build
→ LLM
→ Tool Call
→ Tool Result persisted as Event
→ Context Build
→ LLM
→ Tool Call
→ Context Build
→ LLM
```

Retry 必须基于明确的 Snapshot 和 Generation。除非 Retry Policy 明确要求重新检索，否则同一次请求重试应尽量复用相同的 Context Projection，避免输入漂移。

---

## 6. Context Sources

### 6.1 Fixed Sources

每次请求都必须考虑，但内容可能根据 Agent Role 不同而变化：

- Platform Policy；
- Security Policy；
- Agent Identity 和 Role；
- Runtime Invariants；
- Current Input；
- Output Schema。

### 6.2 Interview Runtime Sources

- `InterviewSession`；
- `InterviewPlan`；
- 当前能力目标和剩余能力；
- 当前 `QuestionEpisode`；
- 最近关闭的 `QuestionEpisode`；
- 当前 Rubric Version；
- Evidence Spans；
- 当前评分或报告阶段。

### 6.3 Memory Sources

- Working Memory；
- Core Memory；
- Deferred Memory Index；
- Deferred Memory Body；
- Episodic Recall；
- Active Skill Instructions。

### 6.4 Knowledge Sources

- JD；
- Resume；
- Question Bank；
- Rubric Library；
- Enterprise Knowledge；
- External Documents。

### 6.5 Tool Sources

- Selected Tool Schemas；
- Tool Call；
- Tool Result；
- Tool Approval / Authorization；
- Artifact References。

### 6.6 Conversation Sources

- Raw Event Projection；
- Rolling Summary；
- Token Tail；
- Exact ID Recall；
- Full-text / Vector Recall。

---

## 7. Context Provider Model

参考 Microsoft Agent Framework 的可组合 Context Provider 和 `source_id` 归因机制，但不允许 Provider 直接修改最终消息列表。

```python
class ContextProvider(Protocol):
    source_id: str

    async def collect(
        self,
        request: ContextBuildRequest,
        snapshot: ContextSnapshot,
    ) -> list[ContextItem]: ...
```

建议首批 Provider：

```text
SystemPolicyProvider
InterviewStateProvider
QuestionEpisodeProvider
HistoryProvider
WorkingMemoryProvider
CoreMemoryProvider
DeferredMemoryProvider
KnowledgeProvider
RecallProvider
SkillProvider
ToolSchemaProvider
```

Provider 规则：

- 必须有稳定且唯一的 `source_id`；
- 返回结构化 `ContextItem`，不返回已经拼好的最终 Prompt；
- 不得修改 Event Store 或共享候选列表；
- 读取必须受 tenant/user/interview/session scope 限制；
- 外部 Provider 应设置 Timeout、结果上限和失败策略；
- Provider 失败是否阻止请求，由来源重要性决定；
- 每个 Item 必须携带 Provenance；
- Provider 返回顺序不能成为隐式业务优先级。

互相独立的 Provider 可以并行收集，但 Builder 应使用统一 Deadline，避免某个 RAG 或外部服务无限阻塞模型调用。

---

## 8. Context Intermediate Representation

所有来源统一转换为 `ContextItem`：

```python
@dataclass(frozen=True)
class ContextItem:
    id: str
    source_id: str
    kind: ContextKind
    content: Any

    scope: ContextScope
    trust_level: TrustLevel
    priority: int
    token_count: int

    protected: bool = False
    atomic_group_id: str | None = None
    dependency_ids: tuple[str, ...] = ()
    provenance: tuple[SourceRef, ...] = ()

    dedup_key: str | None = None
    created_at: datetime | None = None
    expires_at: datetime | None = None
```

推荐的 `ContextKind`：

```text
SYSTEM_POLICY
AGENT_INSTRUCTION
RUNTIME_STATE
INTERVIEW_PLAN
QUESTION_EPISODE
RUBRIC
EVIDENCE
WORKING_MEMORY
CORE_MEMORY
DEFERRED_MEMORY_INDEX
DEFERRED_MEMORY_BODY
SKILL
TOOL_SCHEMA
TOOL_INTERACTION
CONVERSATION_RAW
CONVERSATION_SUMMARY
RECALL_RESULT
KNOWLEDGE_RESULT
CURRENT_INPUT
OUTPUT_SCHEMA
ARTIFACT_REFERENCE
```

`SourceRef` 至少包含：

```python
@dataclass(frozen=True)
class SourceRef:
    source_type: str
    source_id: str
    event_id: str | None = None
    question_id: str | None = None
    artifact_id: str | None = None
    content_hash: str | None = None
```

ContextItem 是内部 IR；Provider 消息、System Prompt 和 Tool Schema 只能在最终 Assembly 阶段生成。

---

## 9. Scope and Trust Model

### 9.1 Scope Filter

任何检索和上下文加载必须优先应用强 Scope：

```text
tenant_id
→ user_id
→ interview_id
→ session_id
→ agent_id / branch
→ question_id
→ source_event_id
```

Exact Scope 和 Exact ID 优先于模糊检索。

### 9.2 Trust Levels

```text
SYSTEM_TRUSTED
RUNTIME_TRUSTED
BUSINESS_TRUSTED
DERIVED_UNTRUSTED
EXTERNAL_UNTRUSTED
USER_CONTROLLED
```

推荐装配层级：

```text
System Layer
├── Platform / Security Policy
├── Agent Role
└── Runtime Invariants

Trusted Task Layer
├── Interview Plan
├── Rubric
└── Working State

Untrusted Evidence Layer
├── Candidate Answer
├── Resume / JD
├── Retrieved Documents
├── Tool Results
└── Conversation Summary

Current Input Layer
└── Current User / Candidate Input
```

候选人回答、简历、JD、网页、Tool Result、Recall 和 Summary 都是数据，不是系统指令。参考 Google ADK 的 Fencing 设计，外部或转交内容应使用明确边界，并声明其中内容不能修改权限、安全策略或 Tool Approval。

---

## 10. Atomic Groups

### 10.1 Tool Interaction Group

```text
assistant reasoning / decision
→ tool_call
→ approval / authorization
→ tool_result
→ assistant interpretation
```

### 10.2 Question Episode Group

```text
QuestionEpisode
├── original_question
├── candidate_answer
├── follow_up_questions
├── follow_up_answers
├── rubric_version
├── evaluation
├── score
├── evidence_spans
└── source_event_ids
```

### 10.3 Group Rules

- 原子组必须整体保留、整体压缩或整体排除；
- 不允许孤立 Tool Call 或 Tool Result；
- 不允许评分脱离对应 Rubric 和 Evidence；
- `OPEN / ANSWERING / FOLLOW_UP / EVALUATING` Episode 不得进入 Summary；
- 只有 `CLOSED` Episode 才能成为 Compact 候选；
- 并行 Tool Call 的所有 sibling result 需要作为同一依赖集合处理；
- Reasoning Signature、Tool Call ID 等 Provider 要求的 opaque state 必须原样保留。

---

## 11. Protected Region

Protected Region 根据业务依赖动态计算，不采用固定最近 K 轮。

### P0：绝对保护

- System / Security Policy；
- Current Input；
- 当前开放的 Question Episode；
- 未完成 Tool Interaction；
- 当前 Provider 所需 opaque state；
- 当前模型输出所需 Schema。

### P1：任务保护

- Interview Plan 当前部分；
- Working Memory；
- 当前能力目标；
- 当前 Rubric Version；
- 当前评分依赖的 Evidence；
- 最近一个关闭的 Question Episode；
- 当前执行 Skill 的必要步骤。

### P2：弹性上下文

- JD / Resume 相关片段；
- Core / Deferred Memory；
- Recall 结果；
- Knowledge Base；
- Rolling Summary；
- 更早的 Token Tail。

### P3：优先缩减

- 旧 Tool Result Body；
- 重复检索结果；
- 旧关闭 Episode；
- 大型日志；
- 已持久化 Artifact Body；
- 已被有效 Summary 完整覆盖的 Raw Event。

Protected Region 是规则集合的并集。P0 如果单独超过输入预算，Builder 不得通用截断，应返回 `PROTECTED_CONTEXT_OVERFLOW`，交由 Chunk、Map-Reduce、专用 Artifact Processing 或业务降级流程处理。

---

## 12. Deduplication

去重必须在 LLM Summary 前优先执行。

去重依据：

- 相同 `event_id / source_id / artifact_id`；
- 相同内容 Hash；
- 相同 Recall Document + Chunk；
- 相同 Tool Result；
- Summary 已经覆盖的 Raw Event；
- 同一事实在 Core Memory、Recall 和 Knowledge 中的重复副本。

去重不能只比较字符串。需要保留优先级最高、Provenance 最强、内容最完整或最接近原始证据的版本。

如果 Summary 和 Raw Event 同时进入上下文，Raw Event 优先；Summary 中对应片段应被移除或标记为已展开，避免双重计权。

---

## 13. Token Budget

可用输入预算：

```text
Input Budget
= Model Context Window
- Output Reservation
- Provider Safety Margin
```

完整请求 Token 计算必须覆盖：

```text
System Instructions
+ Memory
+ Skills
+ Tool Schemas
+ Conversation Projection
+ Retrieved Context
+ Current Episode
+ Current Input
+ Provider Envelope
```

预算采用三段结构：

```text
Fixed Context
+ Protected Context
+ Elastic Context
≤ Input Budget
```

- `Fixed Context`：安全策略、Agent Role、Current Input 和必要 Schema；
- `Protected Context`：当前业务依赖；
- `Elastic Context`：Memory、Recall、Knowledge 和可恢复历史。

不为所有来源永久写死百分比。可以为不同阶段配置最小保留量和上限：

```text
INTERVIEWING
EVALUATING
REPORTING
PLANNING
```

例如 `EVALUATING` 阶段提高 Rubric 和 Evidence 的预算优先级，`PLANNING` 阶段提高 JD、Resume 和能力模型的预算优先级。

Token 统计优先级：

```text
Provider Exact Usage / Tokenizer
→ Model-specific Local Tokenizer
→ Calibrated Estimate
→ Rough Estimate + Larger Safety Margin
```

Builder 必须输出分项统计：

```text
system_tokens
interview_state_tokens
episode_tokens
memory_tokens
skill_tokens
tool_schema_tokens
conversation_tokens
recall_tokens
knowledge_tokens
current_input_tokens
provider_envelope_tokens
reserved_output_tokens
```

---

## 14. Selection Policy

第一版 Selection 使用确定性流水线：

```text
Hard Constraints
→ Scope Filter
→ Permission Filter
→ Trust Classification
→ Deduplication
→ Atomic Grouping
→ Protected Region
→ Priority
→ Relevance
→ Recency
→ Evidence Dependency
→ Token Cost
```

弹性 Context 可以使用以下可解释评分：

```text
score =
    task_relevance
  + evidence_dependency
  + business_priority
  + recency
  + source_quality
  + exact_identifier_match
  - redundancy
  - token_cost
```

选择记录必须说明：

```json
{
  "context_item_id": "...",
  "selected": true,
  "reason": "required_by_active_rubric",
  "score": 0.95,
  "token_count": 420,
  "budget_bucket": "protected"
}
```

被淘汰内容也要记录原因，例如：

```text
OUT_OF_SCOPE
PERMISSION_DENIED
DUPLICATE
SUPERSEDED
LOW_RELEVANCE
BUDGET_EXHAUSTED
EXPIRED
INVALID_DEPENDENCY
```

这样才能区分模型答错是检索失败、选择失败、预算淘汰、压缩错误还是模型推理错误。

---

## 15. Compact Integration

Context Builder 是 Compact 的调用方：

```text
Build Candidate Projection
→ Budget Check
→ Compact Required
→ Persist CompactionEvent
→ Rebuild from New Generation
→ Validate
→ Model Request
```

禁止直接对最终 Prompt 字符串进行无结构截断。

Compact 请求至少包含：

```python
class CompactRequest:
    session_id: str
    expected_generation: int
    pressure_level: PressureLevel
    input_budget: int
    target_budget: int
    protected_item_ids: tuple[str, ...]
    candidate_atomic_group_ids: tuple[str, ...]
```

Compact 成功后：

1. 写入 `CompactionEvent`；
2. 使用 CAS 更新 Projection Generation；
3. Context Builder 从新的 Generation 重新收集和投影；
4. 校验 Summary 覆盖范围和原始事件排除范围；
5. 最多执行配置允许的 Build/Compact 循环次数，防止无限重建。

Compact 失败时不得隐藏未被有效摘要覆盖的语义消息。详细策略见 [compact.md](./compact.md)。

---

## 16. Recall Integration

Recall 用于恢复已经移出当前 Projection 的精确信息，不用于替代原始证据存储。

检索顺序：

```text
Strong Scope Filter
→ Exact Event / Question / Artifact ID
→ Full-text / BM25
→ Vector Search
→ RRF
→ Rerank
→ Context Selection
```

触发方式：

- 用户明确引用过去内容；
- 当前 Rubric 需要历史证据；
- Summary 包含 Evidence Ref；
- Agent 对历史细节不确定；
- 当前回答与历史回答可能矛盾；
- 最终报告需要跨 Question Episode 汇总。

Recall Result 进入上下文前仍需经过 Scope、Trust、Dedup、Budget 和 Provenance 校验。

---

## 17. Context Assembly

推荐的逻辑装配顺序：

```text
1. Platform / Security Policy
2. Agent Identity and Role
3. Runtime Invariants
4. Active Skill Instructions
5. Interview Plan and Working State
6. Current Rubric and Evaluation Contract
7. Core Memory / Deferred Memory Index
8. Rolling Summary
9. Selected History / Token Tail
10. Selected Recall / Knowledge
11. Current Question Episode
12. Current Input
13. Output Schema
14. Selected Tool Schemas
```

这是逻辑顺序，不要求所有 Provider 使用相同消息角色。Provider Adapter 负责根据不同模型的缓存、System Message 和 Tool Schema 规则进行物理转换。

稳定且高复用的前缀应尽量保持顺序和内容稳定，以提高 Prompt Cache 命中率；易变内容放在后部。但缓存优化不能改变 Trust Boundary 或 Evidence 完整性。

---

## 18. Data Contracts

### 18.1 ContextBuildRequest

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

### 18.2 ModelProfile

```python
@dataclass(frozen=True)
class ModelProfile:
    provider: str
    model: str
    context_window: int
    tokenizer: str | None
    supports_system_role: bool
    supports_tool_calls: bool
    supports_prompt_cache: bool
    provider_safety_margin: int
```

### 18.3 ContextPackage

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

### 18.4 ContextProjection Record

每次实际调用模型前持久化或记录：

```json
{
  "projection_id": "...",
  "session_id": "...",
  "run_id": "...",
  "turn_id": "...",
  "generation": 7,
  "model": "...",
  "selected_item_ids": [],
  "rejected_item_ids": [],
  "source_event_ids": [],
  "compaction_event_ids": [],
  "token_breakdown": {},
  "content_hash": "...",
  "created_at": "..."
}
```

Projection Record 用于重放、审计、Bad Case 定位和离线 Evaluation。敏感正文可以只记录 Hash、Source Ref 和安全存储引用。

---

## 19. Validation

发送模型请求前必须检查：

```text
Token Budget Valid
Current Input Present
Current Question Episode Complete
Protected Items Present
Tool Call / Result Paired
No Orphan Tool Result
No Unresolved Required Dependency
Rubric Version Correct
Evidence References Resolvable
No Duplicate Summary + Covered Raw Event
Trust Boundary Preserved
Output Reservation Preserved
Provider Schema Valid
```

校验失败分类：

```text
RETRYABLE_BUILD_ERROR
PROVIDER_DEGRADED
COMPACTION_REQUIRED
PROTECTED_CONTEXT_OVERFLOW
INVALID_ATOMIC_GROUP
INVALID_PROVIDER_REQUEST
GENERATION_CONFLICT
FATAL_POLICY_VIOLATION
```

---

## 20. Transaction and Concurrency

Context Build 使用一致性 Snapshot：

```text
1. Read session generation
2. Capture Event / State snapshot
3. Collect and select context
4. If compacting, commit with expected_generation
5. Rebuild from committed generation
6. Record Context Projection
7. Invoke model
```

当构建期间产生新 Event：

- 当前用户输入和 Tool Result 如果属于本次调用依赖，必须触发重建；
- 无关的后台 Memory Consolidation 可以延迟到下一 Turn；
- Compaction 使用 session lock 或 compare-and-swap；
- 旧 Generation 生成的 ContextPackage 不得覆盖新 Projection；
- Cancellation 后不得提交未完成 Compaction。

---

## 21. Failure and Degradation

### Provider Failure

按来源重要性处理：

- System Policy、Current Episode、Rubric 读取失败：阻止调用；
- Core Memory、Recall、Knowledge 超时：记录降级，在允许时继续；
- Tool Schema 获取失败：移除对应 Tool，不发送不完整 Schema；
- Tokenizer 失败：使用校准估算并增大 Safety Margin。

### Context Overflow

```text
1. Recalculate with exact/updated token information
2. Remove deterministic duplicates
3. Reduce old Tool Result bodies
4. Remove low-priority elastic context
5. Run transactional Compact
6. Rebuild and validate
7. Apply restricted emergency truncation
8. Return protected-context overflow if still too large
```

Provider 返回 Context Overflow 后，Runtime 可以用更大 Safety Margin 重建一次，但必须设置 Retry 上限，防止无限循环。

### Stale or Failed Recall

Recall 失败不能伪装成“没有相关历史”。Trace 中必须区分：

```text
NO_MATCH
TIMEOUT
BACKEND_ERROR
PERMISSION_FILTERED
BUDGET_REJECTED
```

---

## 22. Observability

每次 Context Build 至少记录：

```text
projection_id
generation
phase
model_profile
provider_count
provider_latency
provider_failures
candidate_item_count
selected_item_count
rejected_item_count
protected_item_count
deduplicated_item_count
tokens_by_source
tokens_by_kind
input_budget
reserved_output_tokens
selection_reasons
rejection_reasons
compaction_triggered
recall_triggered
validation_result
build_latency
content_hash
```

Trace 层级：

```text
Context Build Span
├── Snapshot Span
├── Provider Collect Spans
├── Scope / Trust Span
├── Dedup Span
├── Atomic Group Span
├── Protected Region Span
├── Budget Selection Span
├── Compact Span (optional)
├── Assembly Span
├── Validation Span
└── Provider Adaptation Span
```

Debug 工具应支持类似 Hermes `/context` 的分项视图，展示各来源占用、剩余预算、被排除内容及原因。

---

## 23. Evaluation

### Context Selection

- Protected Context Recall；
- Relevant Context Precision；
- Relevant Context Recall；
- Duplicate Context Rate；
- Irrelevant Token Ratio；
- Exact Identifier Recovery。

### Interview Integrity

- Current Episode Completeness；
- Rubric Version Accuracy；
- Evidence Span Recoverability；
- Unsupported Evaluation Rate；
- Compaction 前后评分一致性；
- 最终报告 Evidence Coverage。

### Runtime Correctness

- Tool Call / Result Pairing；
- Resume Context Consistency；
- Retry Input Consistency；
- Context Projection Replay Rate；
- Overflow Recovery Success Rate；
- Generation Conflict Handling。

### Efficiency

- Context Build Latency；
- Provider Latency；
- Token Utilization；
- Token Reduction；
- Prompt Cache Hit Rate；
- Recall Cost；
- Compact Cost；
- End-to-End Cost。

评测不能只比较 Token 数。最终优化目标是：

```text
Task Correctness
+ Evidence Integrity
+ Recoverability
+ Context Precision
+ Runtime Stability
+ Token Efficiency
```

---

## 24. Reference Project Decisions

### Microsoft Agent Framework

采用：

- 可组合 Context Provider；
- 强制 `source_id`；
- 来源归因；
- Atomic Message Group；
- In-run Compaction 思路。

调整：

- Provider 不直接修改最终 Context；
- Provider 顺序不作为隐式优先级；
- Selection 和 Budget 由中央 Builder 统一控制。

### Google ADK

采用：

- Append-only Event 到模型内容的投影；
- Branch / Agent Scope；
- CompactionEvent；
- Compact 后重新生成模型内容；
- Tool Call/Response 恢复与配对；
- Untrusted Context Fencing。

调整：

- Compaction 覆盖范围使用 Event Sequence、Generation 和 Event ID，而不是只依赖 Timestamp。

### Hermes Agent

采用：

- Context Engine 生命周期；
- Preflight Budget Check；
- Provider 实际 Usage 与本地估算结合；
- Token Breakdown；
- 确定性 Tool Result Pruning；
- Context Overflow Recovery。

调整：

- 不使用固定 `protect_first_n / protect_last_n` 作为主要保护规则；
- 使用 Question Episode、Evidence Dependency 和 Token Tail。

### Letta

采用：

- Core / Deferred / Episodic Memory 分层；
- Memory Index 与正文按需加载；
- Memory 和 Conversation History 解耦。

调整：

- Core Memory 设置严格 Token 上限；
- Summary 不直接写入长期 Memory；
- Memory 内容仍需经过当前 Turn 的 Context Selection。

### OpenAI Agents SDK

采用：

- Session History 与 Current Input 分离；
- Turn Preparation；
- Model Input Filter 生命周期；
- Retry 与 Session Persistence 边界；
- 本地 History 和 Server-managed Conversation 区分。

调整：

- Input Callback 只作为扩展点，不承担完整 Context Build。

### LangGraph

采用：

- Workflow State；
- Checkpoint / Resume；
- Node / Edge 调度；
- Durable Execution。

调整：

- LangGraph State 不直接作为完整模型上下文；
- Context Builder 从 State、Event 和 Memory 构造专用 Projection。

---

## 25. Implementation Plan

### V0：Deterministic Context Builder

- 定义 `ContextBuildRequest`、`ContextItem`、`ContextPackage`；
- 实现 Context Provider Registry；
- 实现 Interview State / Episode / History / Tool Provider；
- 实现 Scope、Trust、Dedup 和 Atomic Group；
- 实现 Protected Region；
- 实现模型级 Token Budget 和 Breakdown；
- 实现确定性 Selection；
- 实现 Validator；
- 记录 ContextProjection 和 Selection Reason。

V0 暂不依赖 LLM Summary。先验证短会话和中等长度面试的上下文正确性。

### V1：Compact and Recall

- Typed Tool Reducer；
- Rolling Summary；
- CompactionEvent；
- Generation / CAS；
- Token Tail；
- Exact ID Recall；
- Full-text / Vector Recall；
- Provider Overflow Recovery。

### V2：Memory and Knowledge

- Core / Deferred / Episodic Memory Provider；
- JD、Resume、Question Bank 和 Enterprise Knowledge Provider；
- Reflection 与 Memory Write Policy；
- Context 与 Memory 的冲突、版本和过期处理。

### V3：Evaluation-driven Optimization

- InterviewBench Context 数据集；
- Selection / Compact / Recall Ablation；
- 不同阶段的预算策略；
- 不同模型的 Safety Margin；
- Learned Selector 或 LLM Reranker；
- Prompt Cache 和 Cost 优化。

---

## 26. Final Architecture Decision

Interview AI Platform 的 Context Build 最终采用：

```text
Microsoft Agent Framework
Provider Composition + Source Attribution + Atomic Groups

Google ADK
Event Projection + CompactionEvent + Branch Scope

Hermes Agent
Token Budget + Context Breakdown + Runtime Degradation

Letta
Memory Tiers + On-demand Loading

OpenAI Agents SDK
Turn Lifecycle + Session/Input Boundary + Retry Semantics

LangGraph
State + Checkpoint + Durable Workflow

Interview AI Platform
Question Episode + Rubric + Evidence Integrity
```

最终形成：

> **Centralized Context Builder
>
> + Pluggable Context Providers
> + Provider-neutral Context IR
> + Evidence-aware Protected Region
> + Token-aware Deterministic Selection
> + Append-only Context Projection
> + Transactional Compact and Recall
> + Provider-specific Adaptation
> + Evaluation-driven Optimization**

核心约束：

> **Context Builder 可以决定模型看到什么，但不能改变系统曾经发生过什么；可以压缩对话表达，但不能损坏面试评分所依赖的原始证据。**
