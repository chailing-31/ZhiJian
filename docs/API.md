# API 契约 v1.0

对外业务接口统一经 Spring Boot；AI 服务仅由后端调用。业务内部主键为 `batch_id`（BIGINT），公开批次编号为 `batch_code`（如 `APPLE-2026-001`）。日期时间采用 ISO 8601 并带时区；数据库写入前由后端统一转换。统一错误响应暂定 `{ "code": "...", "message": "..." }`，实现时冻结状态码与字段。

| 方法与路径 | 请求 | 响应/首周状态 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；C 首周实现 |
| `GET /batches` | 可选分页 | 批次列表；C 首周实现 |
| `POST /batches` | `batch_code`, `product`, `variety`, `origin`, `supplier` | `batch_id`, `batch_code`；C 首周实现，重复编号返回 409 |
| `GET /batches/{id}` | 内部 ID | 批次基础信息与事件；C 首周实现 |
| `GET /trace/{batch_code}` | 公开编号 | 仅 `visibility=public` 的事件；C 首周实现 |
| `POST /batches/{id}/inspections` | 图片 | 模型原始框、建议等级、结果图；后续实现 |
| `PATCH /inspections/{id}/review` | 最终等级、审核人、改判原因 | 保留原始模型结果；后续实现 |
| `POST /batches/{id}/processing-advice` | 工艺输入 | `advice_type=rule`、规则依据与建议；后续实现 |
| `POST /batches/{id}/sensor-readings` | 时间戳和温湿度 | 写入与告警结果；后续实现 |
| `POST /alerts/{id}/resolve` | 处置人、说明 | 处置结果；后续实现 |
| `GET /batches/{id}/report` | 内部 ID | 批次汇总；后续实现 |

首周固定验收样例：`GET /batches` 返回包含 `{"batch_id": 1, "batch_code": "APPLE-2026-001", "product": "苹果", "status": "created"}` 的数组；实际 `batch_id` 以数据库生成值为准，客户端不可假定总是 1。`GET /trace/APPLE-2026-001` 只显示 `入厂` 演示事件；其他阶段未发生则显示“暂无记录”。A 与 B 各提交一份独立服务的请求/响应 JSON 示例并在联调前冻结字段。
