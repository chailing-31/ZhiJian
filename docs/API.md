# API 契约 v1.0

对外业务接口统一经 Spring Boot；AI 服务后续仅由后端调用。业务内部主键为 `batch_id`（BIGINT），公开批次编号为 `batch_code`（如 `APPLE-2026-001`）。数据库 DATETIME 按北京时间解释，API 输出 ISO 8601 `+08:00` 时间。当前错误响应采用 Spring Boot 默认格式与 HTTP 状态码；统一错误结构待下一阶段冻结。

| 方法与路径 | 请求 | 响应/首周状态 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；已实现 |
| `GET /batches` | 无 | 批次数组，按内部 ID 倒序；已实现 |
| `POST /batches` | `batch_code`, `product`, `variety`, `origin`, `supplier` | `batch_id`, `batch_code`；已实现，重复编号返回 409，非法编号返回 400 |
| `GET /batches/{id}` | 内部 ID | 批次基础信息与全部事件；已实现，未找到返回 404 |
| `GET /trace/{batch_code}` | 公开编号 | 只含产品、品种、产地以及 `visibility=public` 的事件；已实现，未找到返回 404 |
| `POST /batches/{id}/inspections` | 图片 | 模型原始框、建议等级、结果图；后续实现 |
| `PATCH /inspections/{id}/review` | 最终等级、审核人、改判原因 | 保留原始模型结果；后续实现 |
| `POST /batches/{id}/processing-advice` | 工艺输入 | `advice_type=rule`、规则依据与建议；后续实现 |
| `POST /batches/{id}/sensor-readings` | 时间戳和温湿度 | 写入与告警结果；后续实现 |
| `POST /alerts/{id}/resolve` | 处置人、说明 | 处置结果；后续实现 |
| `GET /batches/{id}/report` | 内部 ID | 批次汇总；后续实现 |

固定验收样例：`GET /batches` 返回包含 `{"batch_id": 1, "batch_code": "APPLE-2026-001", "product": "苹果", "status": "created"}` 的数组；实际 `batch_id` 以数据库生成值为准。`GET /trace/APPLE-2026-001` 只显示 `入厂` 演示事件；其他阶段未发生则显示“暂无记录”。新建批次不会自动伪造后续事件。A 与 B 接入前各提交独立服务的请求/响应 JSON 示例。
