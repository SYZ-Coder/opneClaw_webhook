# API 文档

本文档只描述当前源码中真实存在的接口。

## 1. sender

服务地址：`http://localhost:8081`

### `POST /api/message/wake`

请求：

```json
{
  "text": "Hello OpenClaw"
}
```

说明：

- 调用 OpenClaw `/hooks/wake`
- 代码会自动设置 `mode: "now"`

### `POST /api/message/agent`

请求：

```json
{
  "message": "Summarize this task",
  "agentId": "hooks"
}
```

说明：

- 默认 `agentId` 为 `hooks`
- 实际发送给 OpenClaw 的请求体中会附带：
  - `name: "SpringBoot Demo"`
  - `wakeMode: "now"`
  - `deliver: false`

### `POST /api/message/agent/rpc`

请求：

```json
{
  "message": "Tell me the current time",
  "agentId": "director",
  "sessionKey": "hook:springboot-demo",
  "submitTimeoutMs": 10000,
  "waitTimeoutMs": 30000
}
```

说明：

- 默认 `agentId` 为 `director`
- 默认 `sessionKey` 兜底值来自配置 `openclaw.webhook.default-session-key`
- 返回 JSON 结构化结果

### `POST /api/message/agent/rpc/text`

请求体与 `/api/message/agent/rpc` 相同。

响应：

- `200 text/plain`：返回最终文本
- `204 No Content`：没有拿到最终文本

### `POST /api/message/agent/stream`

请求：

```json
{
  "message": "Run this task",
  "agentId": "hooks"
}
```

响应：

- `Content-Type: text/event-stream`
- 事件名可能为：
  - `accepted`
  - `polling`
  - `completed`
  - `timeout`
  - `error`

### `GET /api/message/result/{runId}`

查询参数：

- `agentId`：可选
- `wait`：可选，默认 `true`

示例：

```bash
curl "http://localhost:8081/api/message/result/your-run-id?agentId=hooks&wait=true"
```

说明：

- 如果 sender 内存缓存里已有结果，会直接返回
- 如果缓存没有结果但传入了 `agentId`，会尝试从本地 OpenClaw session transcript 中恢复文本

### `POST /api/message/custom`

请求：

```json
{
  "endpoint": "agent",
  "message": "Process this payload",
  "agentId": "hooks",
  "data": {
    "source": "demo"
  }
}
```

说明：

- `endpoint` 为空时默认走 `agent`
- 最终会被转发到 `/hooks/{endpoint}`

## 2. receiver

服务地址：`http://localhost:8082`

### `POST /api/webhook/receive`

请求：

```json
{
  "text": "Agent completed task",
  "message": "optional message",
  "sessionKey": "hook:springboot-demo",
  "status": "completed",
  "timestamp": 1710000000000
}
```

响应：

```json
{
  "status": "processed",
  "message": "Webhook processed successfully",
  "data": {
    "text": "Agent completed task",
    "sessionKey": "hook:springboot-demo"
  }
}
```

说明：

- 当前不会校验签名
- 只是把收到的数据存进内存

### `GET /api/webhook/messages`

返回当前内存中的所有 webhook 消息。

### `GET /api/webhook/messages/{messageId}`

按 `messageId` 查询一条消息。

### `DELETE /api/webhook/messages`

清空内存中的消息。

## 3. rpc-agent-loop

服务地址：`http://localhost:8083`

### `POST /api/rpc/agent/wait`

请求：

```json
{
  "message": "Reply with current time",
  "agentId": "director",
  "sessionKey": "hook:springboot-demo",
  "submitTimeoutMs": 10000,
  "waitTimeoutMs": 30000
}
```

说明：

- 先调用 `openclaw gateway call agent`
- 再调用 `openclaw gateway call agent.wait`

### `GET /api/rpc/result/{runId}`

示例：

```bash
curl "http://localhost:8083/api/rpc/result/your-run-id?waitTimeoutMs=30000"
```

## 注意事项

- 当前项目没有引入 Spring Boot Actuator，因此没有 `/actuator/health` 或 `/actuator/metrics`
- 当前项目没有示例启动脚本、测试脚本、Dockerfile、Kubernetes 清单
