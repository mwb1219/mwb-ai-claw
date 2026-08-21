---
title: WebSocket 事件协议
parent: 速查参考
nav_order: 2
---

# WebSocket 事件协议

> 端点：`ws://localhost:8080/ws/agent`（Web 模式）。JSON 文本消息，事件以 JSON Lines 推送。
> 鉴权开启时握手阶段校验 `X-API-Key` / `Authorization: Bearer`（`WsAuthHandshakeInterceptor`）。

## 1. 客户端 → 服务端

```json
{"type":"chat","message":"你好","sessionId":"xxx","agentId":"yyy"}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `type` | 是 | `chat` / `approve` / `reject` / `pending_tasks` |
| `message` | chat 必填 | 用户消息 |
| `sessionId` | 否 | 缺省自动创建新会话 |
| `agentId` | 否 | 指定专家 Agent，缺省自动路由 |
| `layerKey` | approve/reject 必填 | delegate 编排审批层的标识 |

## 2. 服务端 → 客户端

| type | 说明 |
| --- | --- |
| `session` | 会话 ID（data 为 sessionId） |
| `step` | 推理轨迹（思考 / 工具调用 / 观察 / 编排阶段） |
| `token` | 最终回复 token 增量 |
| `tool_name` | 工具名 |
| `tool_args` | 工具参数增量 |
| `reply` | 完整最终回复 |
| `approval` | 审批结果 / 待审批节点列表（JSON 文本） |
| `error` | 错误信息 |
| `done` | 一次请求结束 |

## 3. 完整时序示例

```
→ {"type":"chat","message":"总结一下当前目录"}
← {"type":"session","data":"a1b2c3..."}
← {"type":"step","data":"[Thought] 需要调用工具处理..."}
← {"type":"tool_name","data":"file"}
← {"type":"tool_args","data":"{\"action\":\"list\"}"}
← {"type":"reply","data":"当前目录下有：..."}
← {"type":"done","data":null}
```

## 4. 行为说明

- **并发**：每条消息在独立任务线程执行，事件按序推送
- **断连回收**：连接关闭时取消仍在执行的流式任务（`StreamTaskRegistry`），停止继续消耗 token
- **审批消息**：`approve` / `reject` 唤醒等待中的 delegate 编排线程继续；`pending_tasks` 返回 JSON 数组文本
- **多租户**：scope 由握手阶段解析写入，与 REST 鉴权维度一致

---

相关：[Web 模式使用指南](../guide/web-usage.md) ｜ [REST API 速查](rest-api.md)
