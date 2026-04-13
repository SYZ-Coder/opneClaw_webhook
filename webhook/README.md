# OpenClaw 与 Spring Boot 集成说明

`webhook/` 目录下包含 3 个独立 Spring Boot 模块：

- `sender`：向 OpenClaw 发送请求
- `receiver`：接收 OpenClaw webhook
- `rpc-agent-loop`：演示 RPC `agent.wait` 流程

## 模块端口

- `sender`：`8081`
- `receiver`：`8082`
- `rpc-agent-loop`：`8083`

## sender 接口

基础路径：`/api/message`

- `POST /wake`
- `POST /agent`
- `POST /agent/rpc`
- `POST /agent/rpc/text`
- `POST /agent/stream`
- `GET /result/{runId}`
- `POST /custom`

说明：

- `/agent` 通过 webhook 发送请求，收到 ACK 后可继续轮询本地 session transcript 读取结果
- `/agent/rpc` 和 `/agent/rpc/text` 通过本机 `openclaw gateway call` 调用 RPC
- `/agent/stream` 返回 `text/event-stream`

## receiver 接口

基础路径：`/api/webhook`

- `POST /receive`
- `GET /messages`
- `GET /messages/{messageId}`
- `DELETE /messages`

说明：

- 收到的数据当前保存在内存 `ConcurrentHashMap`
- `messageId` 优先取 `sessionKey`
- 若没有 `sessionKey`，则使用 `unknown-{timestamp}`

## rpc-agent-loop 接口

基础路径：`/api/rpc`

- `POST /agent/wait`
- `GET /result/{runId}`

说明：

- 这个模块只演示 RPC
- 不依赖 receiver
- 不读取本地 session transcript 兜底结果

## 运行方式

```bash
cd webhook/sender
mvn spring-boot:run
```

```bash
cd webhook/receiver
mvn spring-boot:run
```

```bash
cd webhook/rpc-agent-loop
mvn spring-boot:run
```

## 文档

- [API 文档](docs/API.md)
- [启动与配置](docs/SETUP.md)
- [Webhook 原理说明](docs/OPENCLAW-WEBHOOK-说明.md)
- [Agent Loop / RPC 说明](docs/OPENCLAW-AGENT-LOOP-说明.md)
- [RPC 模块 README](rpc-agent-loop/README.md)
