# 启动与配置说明

## 环境要求

- Java 8+
- Maven 3.6+
- 可用的 OpenClaw 网关地址
- 如果要用 sender 的 RPC / 本地 transcript 兜底能力，需要本机可执行 `openclaw`

## 1. 启动 sender

```bash
cd webhook/sender
mvn spring-boot:run
```

默认端口：`8081`

关键配置：

```yaml
openclaw:
  local:
    home: ${OPENCLAW_HOME:${user.home}/.openclaw}
  webhook:
    url: ${OPENCLAW_URL:http://localhost:18789}
    token: ${OPENCLAW_TOKEN:your-secret-token}
    default-session-key: ${OPENCLAW_HOOK_SESSION_KEY:hook:springboot-demo}
    wait-result: ${OPENCLAW_WAIT_RESULT:true}
    wait-timeout-ms: ${OPENCLAW_WAIT_TIMEOUT_MS:30000}
    wait-poll-ms: ${OPENCLAW_WAIT_POLL_MS:400}
  rpc:
    command: ${OPENCLAW_RPC_COMMAND:openclaw}
    submit-timeout-ms: ${OPENCLAW_RPC_SUBMIT_TIMEOUT_MS:10000}
    wait-timeout-ms: ${OPENCLAW_RPC_WAIT_TIMEOUT_MS:30000}
```

说明：

- `openclaw.webhook.url` 可以填 `http://localhost:18789`
- 代码会自动补齐成 `/hooks/`
- `openclaw.local.home` 用于读取本地 session transcript

## 2. 启动 receiver

```bash
cd webhook/receiver
mvn spring-boot:run
```

默认端口：`8082`

关键配置：

```yaml
receiver:
  openclaw:
    webhook:
      secret: ${OPENCLAW_WEBHOOK_SECRET:your-webhook-secret}
```

说明：

- 当前代码会读取这个配置
- 但没有真正做签名校验，只是预留扩展位

## 3. 启动 rpc-agent-loop

```bash
cd webhook/rpc-agent-loop
mvn spring-boot:run
```

默认端口：`8083`

说明：

- 该模块直接调用本机 `openclaw gateway call`
- 不负责接收 webhook

## 4. 编译验证

可分别执行：

```bash
cd webhook/sender
mvn clean test
```

```bash
cd webhook/receiver
mvn clean test
```

```bash
cd webhook/rpc-agent-loop
mvn clean test
```

如果模块内没有测试类，Maven 也应至少完成编译与打包阶段而不报源码错误。

## 5. OpenClaw 对接建议

这个仓库不包含 OpenClaw 服务本身的配置文件，但根据 sender 的实现，你至少需要保证：

- OpenClaw 网关地址可达
- `/hooks` 已启用
- 调用使用的 Bearer Token 与 sender 配置一致
- 若 sender 需要从本地 transcript 恢复结果，本机有对应 agent 的 session 文件

## 6. 当前未实现的能力

以下内容在当前仓库中未实现，因此不要按这些思路部署：

- `/actuator/health`、`/actuator/metrics`
- Dockerfile
- Kubernetes YAML
- Systemd 服务文件
- 一键启动脚本
- 自动化测试脚本
