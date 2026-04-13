# OpenClaw Agent Loop / RPC 说明

## 核心概念

OpenClaw 的 agent 调用可以理解为“两段式”：

1. 提交任务
2. 等待任务结束并读取结果

在当前仓库中，这个模式对应：

- sender 的 `/api/message/agent/rpc`
- sender 的 `/api/message/agent/rpc/text`
- rpc-agent-loop 模块的 `/api/rpc/agent/wait`

## `agent` 与 `agent.wait`

### `agent`

作用：

- 提交任务
- 返回 `runId`

典型意义：

- 请求已受理
- 任务开始异步执行

### `agent.wait`

作用：

- 根据 `runId` 等待任务结束

注意：

- 有些场景下 `agent.wait` 会直接返回最终文本
- 有些场景下只返回状态，不直接给最终文本

## 当前项目里的两种处理方式

### 1. sender 模块

sender 的 RPC 能力更完整：

- 先执行本机 `openclaw gateway call agent`
- 再执行 `openclaw gateway call agent.wait`
- 如果 `agent.wait` 没拿到文本，会进一步尝试从本地 OpenClaw session transcript 提取 assistant 回复

所以 sender 更适合“尽量拿到最终文本”的业务接入场景。

### 2. rpc-agent-loop 模块

这个模块更纯粹，只演示：

- `agent`
- `agent.wait`

它不会做 transcript 兜底读取，因此更适合教学和排查 RPC 调用本身。

## sender 中与 Agent Loop 相关的接口

- `POST /api/message/agent/rpc`
- `POST /api/message/agent/rpc/text`
- `POST /api/message/agent/stream`
- `GET /api/message/result/{runId}`

## 什么时候用哪个接口

- 想要结构化 JSON 结果：`/api/message/agent/rpc`
- 想直接要文本：`/api/message/agent/rpc/text`
- 想看执行过程：`/api/message/agent/stream`
- 已经有 `runId`，想稍后再查：`/api/message/result/{runId}`

## 常见问题

### 没有 `runId`

说明提交阶段就失败了，应先排查：

- `openclaw` 命令是否可执行
- 网关地址是否可用
- token 是否正确

### `agent.wait` 成功但没有文本

这并不一定是错误，可能只是当前网关返回了状态但没直接返回最终文本。

在 sender 模块里，代码会继续尝试：

- 从本地 `OPENCLAW_HOME` 对应目录读取 session transcript

### 为什么 rpc-agent-loop 没有 sender 那么“智能”

因为它的目标是独立演示最小 RPC 链路，而不是做完整业务兜底。
