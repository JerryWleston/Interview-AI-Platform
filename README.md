# Interview AI Platform

面向真实招聘场景的智能面试 Agent 平台，也是一个用于研究和实现 Long-Horizon Agent Runtime 的工程项目。

平台以一场完整面试为执行主线：读取职位与候选人资料，制定面试计划，动态提问和追问，调用工具核验信息，持续记录证据，最终生成可解释、可回放的评分与面试报告。

项目不以简单封装多个 Agent Framework 为目标，而是以 LangGraph 作为 Workflow Kernel，自行实现 Context、Memory、Tool Runtime、Tracing、Evaluation 等关键能力。

## 核心目标

- 根据 JD、简历和题库生成结构化面试计划。
- 根据候选人的实时回答动态追问，而不是机械执行固定题目列表。
- 在长时间面试中保持任务状态、上下文连续性和事实一致性。
- 将评分结论关联到原始问答和明确的 Evidence Span。
- 支持 Checkpoint、Resume、回放、审计和 Bad Case 分析。
- 通过 InterviewBench 持续评估 Agent 的提问、工具使用、上下文和评分质量。

## 面试执行流程

```text
创建面试
  ↓
加载 JD / 简历 / 题库 / 企业资料
  ↓
生成 Interview Plan 与能力覆盖矩阵
  ↓
提问 → 候选人回答 → 证据提取
  ↓                         │
动态追问 / 调整计划 ← 能力缺口判断
  ↓
单题评分与 Question Episode 归档
  ↓
综合评分、风险说明与面试报告
  ↓
Trace 回放 → Evaluation → Bad Case 优化
```

一次问题及其追问、回答、评分和证据构成一个 `Question Episode`。只有已经关闭的 Episode 才能进入历史压缩；当前问答和评分所依赖的证据始终受到保护。

## 整体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                        Product Layer                         │
│  Interview Console · Candidate Session · Report · Replay    │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│                     Interview Agent Layer                    │
│  Interviewer · Planner · Follow-up · Evaluator · Reporter   │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│                       Agent Runtime                          │
│  Agent Loop · State · Routing · Checkpoint · Resume          │
│  Planning / Replanning · Handoff · Cancel / Recovery         │
└──────────────┬───────────────┬───────────────┬───────────────┘
               │               │               │
┌──────────────▼──────┐ ┌──────▼────────┐ ┌────▼──────────────┐
│  Context Engine     │ │ Memory System │ │   Tool Runtime    │
│  Build / Select     │ │ Working/Core  │ │ Registry / MCP    │
│  Budget / Compact   │ │ Deferred      │ │ Timeout / Retry   │
│  Projection / Recall│ │ Episodic      │ │ Permission        │
└──────────────┬──────┘ └──────┬────────┘ └────┬──────────────┘
               │               │               │
┌──────────────▼───────────────▼───────────────▼───────────────┐
│                    Data & Knowledge Layer                    │
│ Event Store · Checkpoint Store · Artifact Store · Vector DB │
│ JD · Resume · Question Bank · Rubric · Enterprise Knowledge │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│               Trace · Observability · Evaluation             │
│ Run / Turn / Span · Token / Cost · InterviewBench · Dataset │
└──────────────────────────────────────────────────────────────┘
```

## 核心模块

### Interview Agent

Interview Agent 负责业务决策，不直接承担底层上下文和持久化工作。

- `Planner`：根据岗位能力模型生成面试计划和覆盖目标。
- `Interviewer`：选择下一道题并控制面试节奏。
- `Follow-up`：根据回答中的缺口、矛盾和亮点生成追问。
- `Evaluator`：依据 Rubric 和原始 Evidence 进行单题评分。
- `Reporter`：汇总各 Question Episode，生成最终报告。

初期采用单 Agent 加结构化节点的方式实现；只有当隔离上下文、并行执行或职责边界确有需要时，再引入 Supervisor / Worker 和 Agent Handoff。

### Agent Runtime

底层 Workflow Kernel 采用 LangGraph，负责：

- State 与 Node / Edge 调度；
- Conditional Routing；
- Checkpoint / Resume；
- Durable Execution；
- Cancel、Retry 和失败恢复。

平台在其上实现自己的 Agent Loop、Context Build、Memory、Tool、Trace 和 Evaluation，避免让业务逻辑直接依赖特定模型 Provider。

### Context Engine

Context Builder 是每次模型调用前的统一入口，负责决定模型在当前 Turn 中能够看到什么：

```text
System Instructions
+ Core Memory / Memory Index
+ Active Skill
+ Selected Tool Schemas
+ Conversation Projection
+ Retrieved Context
+ Current Question Episode
+ Current Input
```

构建流程：

```text
收集候选上下文
→ Scope 与权限过滤
→ 去重、排序和相关性选择
→ 保护当前任务依赖
→ 计算完整请求 Token
→ 必要时触发 Compact
→ 生成 Context Projection
→ 最终校验并调用模型
```

Context Builder 负责选择和装配；Compact 只负责在预算不足时生成信息密度更高、可恢复的上下文视图。详细设计见 [contextbuild.md](./contextbuild.md) 和 [compact.md](./compact.md)。

### Memory System

| Memory 类型                        | 保存内容                                           | 加载方式                                     |
| ---------------------------------- | -------------------------------------------------- | -------------------------------------------- |
| **Working Memory**           | 当前任务、面试进度、能力覆盖、追问线索和待解决问题 | 随 Checkpoint 保存，按当前执行状态进入上下文 |
| **Core Memory**              | Persona、稳定偏好、长期规则、关键背景和顶层索引    | 精简后进入 System Context                    |
| **Deferred Memory**          | 用户画像、项目细节、架构说明和经验总结             | 默认只加载索引与描述，正文按需读取           |
| **Skills**                   | 可复用流程、脚本和模板                             | 先加载名称和描述，匹配任务后加载正文         |
| **Episodic Memory / Recall** | 原始对话、工具调用、工具结果和事件时间             | 通过 ID、全文和向量检索召回                  |
| **Knowledge Base**           | JD、简历、题库、Rubric、企业资料和外部文档         | 独立文档检索，按任务需要进入上下文           |

Memory 表示系统长期保存的信息；Context 表示某一轮模型实际可见的信息。两者不能等同。详细设计见 [memory.md](./memory.md)。

### Context Compact

当完整模型请求接近 Context Window 上限时，系统按压力等级逐步处理，并在每一步后重新计算 Token：

1. 确定性去重，清理重复 Recall 和冗余状态。
2. 使用 Typed Tool Reducer 压缩历史 Tool Result。
3. 将已经关闭的旧 Question Episode 合并进结构化 Rolling Summary。
4. 隐藏已被有效摘要完整覆盖的原始历史，保留 Token Tail。
5. Emergency 状态下，仅按结构边界裁剪可恢复的日志、文件和检索片段。

System Instructions、当前输入、当前 Question Episode、Rubric、评分证据和未完成的 Tool Interaction 不参与通用硬截断。原始事件始终保留在 Append-only Event Store 中。

### Tool Runtime

Tool Runtime 为 Agent 提供统一、可治理的外部能力：

- Tool Registry 与 Schema；
- Function Tool 与 MCP；
- Timeout、Retry、Idempotency；
- Permission 与 Approval；
- Sandbox；
- Tool Observation 与 Artifact 引用。

工具调用及结果作为完整原子组写入 Event Store，避免在压缩、恢复或重放时出现孤立的 `tool_call` 或 `tool_result`。

### Event、Trace 与 Evidence

所有关键行为首先写入 Append-only Event Store：

```text
Run
├── Planning Span
├── Context Build Span
├── Model Call Span
├── Tool Call Span
├── Memory Read / Write Span
├── Question Episode
│   ├── Question
│   ├── Candidate Answer
│   ├── Follow-up
│   ├── Rubric Version
│   ├── Evaluation / Score
│   └── Evidence Spans
└── Report Generation Span
```

最终评分必须回查原始 `question_id`、`source_event_id` 和 Evidence Span，不能把 Rolling Summary 或向量检索结果作为唯一评分依据。

### Evaluation

InterviewBench 用于把线上 Trace 转化为可复现的数据集和回归测试，重点评估：

- 面试计划与能力覆盖；
- 问题质量和追问有效性；
- Tool Selection / Parameter Accuracy；
- Context Precision 与历史事实恢复；
- Memory Write Precision / Retrieval Recall；
- Rubric 遵循和 Evidence Grounding；
- 压缩前后的评分一致性；
- Checkpoint / Resume 一致性；
- Latency、Token 和 Cost。

优化闭环：

```text
Trace → Dataset → Evaluation → Bad Case
      → Root Cause → Optimization → Regression Test
```

## 核心数据对象

| 对象                  | 作用                                           |
| --------------------- | ---------------------------------------------- |
| `InterviewSession`  | 一场面试的身份、配置、状态和生命周期           |
| `InterviewPlan`     | 能力目标、题目计划、时间预算和动态覆盖情况     |
| `QuestionEpisode`   | 一道题及其回答、追问、评分和证据的业务原子单元 |
| `Event`             | 不可变的运行事实，是恢复、审计和回放的基础     |
| `Checkpoint`        | Runtime 可恢复状态，不替代 Event Store         |
| `ContextProjection` | 某一 Turn 实际提供给模型的上下文视图           |
| `CompactionEvent`   | 对历史上下文的派生压缩结果及其 Provenance      |
| `MemoryRecord`      | 经验证后写入的长期记忆及其作用域和版本         |
| `Artifact`          | 大型日志、文件、媒体和工具结果的外部引用       |
| `EvaluationResult`  | 基于 Trace、原始证据和评测集生成的指标结果     |

## 设计原则

1. **Evidence First**：评分结论必须能够定位到原始候选人回答。
2. **Append-only History**：压缩只改变模型视图，不修改已经发生的事实。
3. **Context Is a Projection**：模型上下文由 Builder 动态构建，不等于数据库历史。
4. **Memory Is Derived**：长期记忆必须经过 Reflection、来源校验和写入策略。
5. **Recoverable by Default**：长会话支持 Checkpoint、Resume、Recall 和 Replay。
6. **Provider Neutral**：业务层不直接绑定某一个 LLM Provider。
7. **Evaluation Driven**：架构选择最终通过 InterviewBench 和 Bad Case 验证。
8. **Single Agent First**：先完成可靠的单 Agent 闭环，再按实际需要扩展 Multi-Agent。

## 实施路线

### Phase 1：最小面试闭环

- 定义 `InterviewSession`、`InterviewPlan`、`QuestionEpisode` 和 `Event`。
- 实现创建面试、提问、回答、追问、单题评分和最终报告。
- 建立基础 Trace，并确保评分能够关联 Evidence Span。

### Phase 2：Context 与长会话

- 实现统一 Context Builder 和 Token Breakdown。
- 实现 Protected Region、Token Tail 和确定性 Tool Reducer。
- 接入 Rolling Summary、CompactionEvent 和 Recall Recovery。

### Phase 3：Memory 与 Knowledge

- 实现 Working / Core / Deferred / Episodic Memory。
- 接入 JD、简历、题库和企业资料检索。
- 增加 Reflection、Memory Write Policy 和冲突处理。

### Phase 4：可靠性与评测

- 完善 Checkpoint / Resume、并发控制、失败回滚和回放。
- 建立 InterviewBench、Bad Case 数据集和自动回归。
- 基于指标优化上下文、追问、评分、延迟与成本。

### Phase 5：高级 Agent 能力

- Planning / Replanning；
- Skill Runtime；
- Supervisor / Worker；
- Agent Handoff、并行执行和冲突处理。

## 当前设计文档

- [Memory Design](./memory.md)
- [Context Build Design](./contextbuild.md)
- [Compact Design](./compact.md)
- [Runtime Design](./runtime.md)
- [Reference Architecture](./reference.md)

## 参考方向

- **LangGraph**：Workflow、State、Checkpoint 与 Durable Execution。
- **Letta**：Memory 分层和按需加载。
- **Microsoft Agent Framework**：Context、Compaction 和 Multi-Agent 模式。
- **OpenAI Agents SDK**：Agent Loop、Tool、Handoff 与 Trace。
- **Google ADK**：Session、Event、Evaluation 与工程化组织。
- **Hermes Agent**：Tool Runtime、Context Management 与长任务实践。
