## memory design(reference letta)

| Memory 类型 | 保存内容 | 加载方式 |
|---|---|---|
| **Working Memory** | 当前任务状态、当前题目、追问线索、待解决问题 | 随当前会话进入上下文，通过 Checkpoint 保存 |
| **Core Memory** | Persona、稳定用户偏好、长期行为规则、关键背景、顶层记忆索引 | 精简后直接进入 System Prompt |
| **Deferred Memory** | 用户详细画像、项目细节、架构说明、经验总结和参考资料，以 Markdown 保存 | 一级、二级索引及简短文件描述进入 System Prompt；文件正文按需读取
| **Skills** | 可复用的多步骤操作流程、脚本和模板 | Skill 名称、描述进入上下文；匹配任务后加载正文 |
| **Episodic Memory / Recall** | 原始历史对话、工具调用、工具结果、事件时间 | 数据库持久化，通过检索召回；保留消息 ID，并支持前后文扩展 |
| **Knowledge Base** | 简历、JD、题库、企业资料和外部文档 | 独立的文档 RAG，按需进入当前上下文 |

## compact design(reference microsoft agent framework)

- 1、压缩tools
- 2、调用llm将对话压缩成摘要，保留K轮对话
- 3、硬截断若干轮对话
- 4、硬截断字符