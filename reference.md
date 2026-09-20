# Interview AI Platform

## 基本介绍

面向 AI Agent 场景设计的面试智能体平台。

项目以 Interview Agent 为业务载体，
重点研究 Long-Horizon Agent 在以下问题上的工程实现：

- Agent Runtime
- Context Engineering
- Long-term Memory
- Planning / Replanning
- Tool Runtime
- Multi-Agent
- Checkpoint / Resume
- Trace / Observability
- Evaluation / Benchmark
- Bad Case Optimization

项目不以简单集成多个 Agent Framework 为目标，
而是分析主流开源 Agent Runtime 的设计思想，
在 LangGraph 之上实现自己的 Agent Harness。

## Runtime

### Workflow / State / Checkpoint

底层执行内核采用 LangGraph。

负责：

- State 管理
- Node / Edge 调度
- Conditional Routing
- Checkpoint
- Resume
- Durable Execution

LangGraph 只作为 Workflow Kernel，
Context、Memory、Tool、Multi-Agent、
Tracing、Evaluation 等核心能力自行实现。


## Context Engineering & Memory

设计参考：Letta

重点实现：

- Working Memory
- Episodic Memory
- Semantic Memory
- User Profile Memory
- Memory Write Policy
- Memory Retrieval Policy
- Memory Consolidation
- Context Budget
- Context Compression
- Context Selection


## Agent Loop & Tracing

设计参考：OpenAI Agents SDK

重点研究：

- Agent Loop
- Tool Call
- Handoff
- Run / Turn / Span
- Trace Hierarchy

自行实现统一 Trace：

Run
├── Planning
├── Model Call
├── Context Build
├── Memory Read
├── Tool Call
├── Handoff
├── Reflection
└── Evaluation


## Multi-Agent

设计参考：Microsoft Agent Framework

重点研究：

- Supervisor / Worker
- Agent Handoff
- Shared State
- Private Context
- Message Passing
- Parallel Execution
- Conflict Resolution


## Tool Runtime

设计参考：Hermes Agent

重点实现：

- Tool Registry
- Tool Schema
- MCP
- Timeout
- Retry
- Idempotency
- Permission
- Sandbox
- Tool Observation


## Evaluation

设计参考：Google ADK

自行实现 InterviewBench。

Evaluation 包含：

- Task Success
- Tool Selection Accuracy
- Tool Parameter Accuracy
- Trajectory Quality
- Context Precision
- Memory Write Precision
- Memory Retrieval Recall
- Planning Quality
- Replanning Success Rate
- Latency
- Token Cost

形成：

Trace
→ Dataset
→ Evaluation
→ Bad Case
→ Root Cause
→ Optimization
→ Regression Test