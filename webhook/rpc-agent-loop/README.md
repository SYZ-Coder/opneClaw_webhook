# RPC Agent Loop Demo

这是一个独立模块，用来演示 OpenClaw 的 `agent + agent.wait` RPC 流程。

## 启动

```bash
cd webhook/rpc-agent-loop
mvn spring-boot:run
```

默认端口：`8083`

## 接口

### `POST /api/rpc/agent/wait`

请求示例：

```json
{
  "message": "Reply with current time",
  "agentId": "director",
  "sessionKey": "hook:springboot-demo",
  "submitTimeoutMs": 10000,
  "waitTimeoutMs": 30000
}
```

行为：

- 调用 `openclaw gateway call agent`
- 取得 `runId`
- 调用 `openclaw gateway call agent.wait`
- 返回结构化 JSON

### `GET /api/rpc/result/{runId}`

示例：

```bash
curl "http://localhost:8083/api/rpc/result/your-run-id?waitTimeoutMs=30000"
```

## 与 sender 模块的区别

- 这里是最小化 RPC 示例
- 不接收 webhook
- 不从本地 session transcript 补结果
- 更适合单独验证 RPC 能否跑通
