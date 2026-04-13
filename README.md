# OpenClaw Webhook Spring Boot Demo

这个仓库是一个基于 Spring Boot 的 OpenClaw 集成示例，包含 3 个独立模块：

- `webhook/sender`：向 OpenClaw `/hooks/*` 发送请求
- `webhook/receiver`：接收 OpenClaw 回调 webhook
- `webhook/rpc-agent-loop`：演示 `agent + agent.wait` 的 RPC 调用链

## 目录结构

```text
.
├── README.md
├── DEPLOYMENT_STATUS.md
├── this-summary.md
└── webhook
    ├── README.md
    ├── docs
    │   ├── API.md
    │   ├── SETUP.md
    │   ├── OPENCLAW-WEBHOOK-说明.md
    │   └── OPENCLAW-AGENT-LOOP-说明.md
    ├── sender
    ├── receiver
    └── rpc-agent-loop
```

## 运行环境

- Java 8+
- Maven 3.6+
- 可访问的 OpenClaw 网关
- 如果要使用 sender 的“结果轮询 / RPC”能力，本机还需要可用的 `openclaw` 命令与本地 OpenClaw 会话目录

## 模块说明

### 1. `webhook/sender`

端口：`8081`

主要接口：

- `POST /api/message/wake`
- `POST /api/message/agent`
- `POST /api/message/agent/rpc`
- `POST /api/message/agent/rpc/text`
- `POST /api/message/agent/stream`
- `GET /api/message/result/{runId}`
- `POST /api/message/custom`

实现特点：

- 通过 `WebClient` 调用 OpenClaw `/hooks`
- `openclaw.webhook.url` 可填写主机根地址，代码会自动补齐 `/hooks/`
- 普通 `agent` 调用会先获得 ACK，再按本地会话记录尝试补全最终回复
- `agent/rpc*` 路径会调用本机 `openclaw gateway call`
- `agent/stream` 使用 SSE 返回 `accepted / polling / completed / timeout / error`

### 2. `webhook/receiver`

端口：`8082`

主要接口：

- `POST /api/webhook/receive`
- `GET /api/webhook/messages`
- `GET /api/webhook/messages/{messageId}`
- `DELETE /api/webhook/messages`

实现特点：

- 当前仅做演示用途
- 收到的 webhook 数据保存在内存中，服务重启后会丢失
- `receiver.openclaw.webhook.secret` 目前只是占位配置，代码里没有真正的签名校验逻辑

### 3. `webhook/rpc-agent-loop`

端口：`8083`

主要接口：

- `POST /api/rpc/agent/wait`
- `GET /api/rpc/result/{runId}`

实现特点：

- 直接通过本机 `openclaw gateway call agent` 与 `agent.wait` 调用网关
- 适合单独理解 `runId + wait` 流程
- 这个模块不读取本地 session 文件；最终文本是否能拿到，取决于 `agent.wait` 返回内容

## 快速启动

分别在三个目录中执行：

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

## 常用配置

`webhook/sender/src/main/resources/application.yml`

```yaml
server:
  port: 8081

openclaw:
  local:
    home: ${OPENCLAW_HOME:${user.home}/.openclaw}
  webhook:
    url: ${OPENCLAW_URL:http://localhost:18789}
    token: ${OPENCLAW_TOKEN:your-secret-token}
    default-session-key: ${OPENCLAW_HOOK_SESSION_KEY:hook:springboot-demo}
    wait-result: ${OPENCLAW_WAIT_RESULT:true}
```

`webhook/receiver/src/main/resources/application.yml`

```yaml
server:
  port: 8082

receiver:
  openclaw:
    webhook:
      secret: ${OPENCLAW_WEBHOOK_SECRET:your-webhook-secret}
```

## 文档索引

- [整体说明](webhook/README.md)
- [API 文档](webhook/docs/API.md)
- [环境与启动说明](webhook/docs/SETUP.md)
- [Webhook 机制说明](webhook/docs/OPENCLAW-WEBHOOK-说明.md)
- [Agent Loop / RPC 说明](webhook/docs/OPENCLAW-AGENT-LOOP-说明.md)
- [RPC 模块说明](webhook/rpc-agent-loop/README.md)

## 发布到 GitHub 前的检查结论

已确认并处理：

- 固定个人路径 `C:/Users/admin/...` 已替换为通用默认值
- 本地产物目录已加入 `.gitignore`

仍需你在发布前自行确认：

- 是否存在真实的 `OPENCLAW_TOKEN`、`OPENCLAW_WEBHOOK_SECRET` 等私密配置文件未被一并提交
- 是否需要保留 `.idea/`、`.npm-cache/`、`.puppeteer-cache/` 等本地目录中的已有文件

当前源码仓库本身没有看到写死的真实 token，但未跟踪文件仍建议复查一次再推送。
