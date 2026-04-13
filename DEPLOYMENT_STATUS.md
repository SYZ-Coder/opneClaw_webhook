# 文档与发布整理状态

更新时间：`2026-04-13`

## 本次整理目标

- 重新核对仓库中所有 Markdown 文档与当前源码实现是否一致
- 去除无法从代码验证的描述
- 排查明显的个人隐私信息与本地产物
- 让仓库达到可发布到 GitHub 的状态

## 已完成

- 统一重写根目录与 `webhook/` 下的主要说明文档
- 修正文档中的以下问题：
  - 错误的目录名与启动路径
  - 声称存在但仓库中并不存在的脚本
  - 文档里提到但代码没有实现的 `actuator`、`metrics`、Docker、Kubernetes、Systemd 等内容
  - 与实际接口不一致的请求/响应示例
  - 乱码与编码可读性问题
- 修正默认 OpenClaw 本地目录：
  - 从 `C:/Users/admin/.openclaw`
  - 改为 `${user.home}/.openclaw`
- 新增 `.gitignore`，忽略：
  - `.idea/`
  - `.npm-cache/`
  - `.puppeteer-cache/`
  - `**/target/`
  - `**/*.log`

## 当前源码实际能力

### sender

- 支持 `/wake`
- 支持 `/agent`
- 支持 `/agent/rpc`
- 支持 `/agent/rpc/text`
- 支持 `/agent/stream`
- 支持按 `runId` 查询结果
- 支持自定义 `/custom` 路径转发

### receiver

- 接收 webhook
- 将消息缓存在内存
- 提供查询和清空接口
- 尚未实现真实的 webhook 签名校验

### rpc-agent-loop

- 直接走 `openclaw gateway call agent`
- 直接走 `openclaw gateway call agent.wait`
- 用于演示 `runId + wait` RPC 模式

## 发布建议

可以发布到 GitHub，但建议在推送前再做一次人工确认：

- 不要提交本地缓存目录内容
- 不要提交 `target/` 编译产物
- 不要提交任何本地 `.env`、私钥、真实 token 或带个人主机信息的配置

## 不建议在 README 中宣称的内容

以下内容当前代码并未实现，因此文档中已移除：

- 一键启动脚本
- 自动化测试脚本
- Actuator 健康检查与监控指标
- Docker / Kubernetes / Systemd 部署方案
- 生产级 webhook 签名校验
- 持久化存储
