# OpenClaw Webhook 说明

## 什么是 webhook

在这个项目语境下，webhook 指的是 OpenClaw 网关暴露的 `/hooks/*` HTTP 接口。

sender 模块就是这个接口的调用方，receiver 模块则模拟“业务系统接收 OpenClaw 回调”的场景。

## 当前项目里 webhook 的两种方向

### 1. Java 服务调用 OpenClaw

由 `webhook/sender` 完成：

- `/api/message/wake` -> `/hooks/wake`
- `/api/message/agent` -> `/hooks/agent`
- `/api/message/custom` -> `/hooks/{endpoint}`

### 2. OpenClaw 回调 Java 服务

由 `webhook/receiver` 完成：

- OpenClaw 或测试脚本向 `/api/webhook/receive` 发 POST
- receiver 把 payload 存到内存，供后续查询

## 当前代码中的 webhook 特点

- sender 会自动补齐 `/hooks/` 路径
- sender 使用 Bearer Token 调用 OpenClaw
- 普通 `agent` 流程是“先接受、后补结果”
- receiver 暂时没有签名验签

## 普通 agent 流程

1. 调用 `POST /api/message/agent`
2. sender 向 OpenClaw 发送 `/hooks/agent`
3. OpenClaw 先返回 ACK，通常包含 `runId`
4. sender 根据配置决定是否继续等待结果
5. 若启用了等待，sender 会尝试从本地 OpenClaw session transcript 中读取 assistant 文本

## 为什么会有“ACK 成功但最终文本为空”

因为 `/hooks/agent` 在设计上通常是异步受理：

- HTTP 返回成功，只代表请求被接收
- 不保证最终结果已在同一个响应里返回

这也是为什么当前项目里又提供了：

- `/api/message/result/{runId}`
- `/api/message/agent/stream`
- `/api/message/agent/rpc`

## 当前项目的边界

receiver 只是演示 webhook 接收，不是完整生产实现：

- 数据只存内存
- 服务重启会丢失
- 没有签名校验
- 没有持久化或重试队列
