# API 契约 v1.1（A3 质检接入）

对外业务接口统一经 Spring Boot；AI 服务仅由后端调用；A3 质检接口在 `inspection` profile 下启用。业务内部主键为 `batch_id`（BIGINT），公开批次编号为 `batch_code`（如 `APPLE-2026-001`）。数据库 DATETIME 按北京时间解释，API 输出 ISO 8601 `+08:00` 时间。当前错误响应采用 Spring Boot 默认格式与 HTTP 状态码；统一错误结构待下一阶段冻结。

| 方法与路径 | 请求 | 响应/首周状态 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；已实现 |
| `GET /batches` | 无 | 批次数组，按内部 ID 倒序；已实现 |
| `POST /batches` | `batch_code`, `product`, `variety`, `origin`, `supplier` | `batch_id`, `batch_code`；已实现，重复编号返回 409，非法编号返回 400 |
| `GET /batches/{id}` | 内部 ID | 批次基础信息与全部事件；已实现，未找到返回 404 |
| `GET /trace/{batch_code}` | 公开编号 | 只含产品、品种、产地以及 `visibility=public` 的事件；已实现，未找到返回 404 |
| `POST /batches/{id}/inspections` | multipart `image` + `Idempotency-Key` UUID | A3 实现；保存原始预测和图片，返回质检记录。无自动等级 |
| `PATCH /inspections/{id}/review` | 版本号、逐候选标记、图像结论、复核人、说明 | A3 实现；追加复核版本，不改原预测。字段见 A3 接口说明 |
| `POST /batches/{id}/processing-advice` | 工艺输入 | `advice_type=rule`、规则依据与建议；后续实现 |
| `POST /batches/{id}/sensor-readings` | 时间戳和温湿度 | 写入与告警结果；后续实现 |
| `POST /alerts/{id}/resolve` | 处置人、说明 | 处置结果；后续实现 |
| `GET /batches/{id}/report` | 内部 ID | 批次汇总；后续实现 |

固定验收样例：`GET /batches` 返回包含 `{"batch_id": 1, "batch_code": "APPLE-2026-001", "product": "苹果", "status": "created"}` 的数组；实际 `batch_id` 以数据库生成值为准。初始种子中 `GET /trace/APPLE-2026-001` 只显示 `入厂` 演示事件；其他阶段未发生则显示“暂无记录”。新建批次不会自动伪造后续事件。A 与 B 接入前各提交独立服务的请求/响应 JSON 示例。

## A3 新增接口与运行条件

先执行 `database/migrations/20261001_A3_inspection_integration.sql`，以 `inspection` profile 启动 Spring Boot。完整输入输出、复核规则、错误、安全和存储说明见 [AI_INTEGRATION_A3.md](AI_INTEGRATION_A3.md)。

当前本地 Demo 的 AI 运行配置冻结为 `confidence_threshold=0.25`、`iou_threshold=0.50`、`image_size=640`；该配置属于模型运行参数而不是业务 API 字段约束。冻结依据和限制见 [AI_MODEL_FREEZE.md](AI_MODEL_FREEZE.md)。

| 方法与路径 | 返回 |
| --- | --- |
| `GET /inspection-service/ready` | `ready`、`database_ready`、`model_ready` 与说明；HTTP 200 不等于 ready=true |
| `GET /batches/{id}/inspections` | 此批次最近 100 条 A3 历史；旧表中没有 payload 的记录不在此列表 |
| `GET /inspections/{id}` | 已保存模型原结果、同源图片地址与全部复核版本 |
| `GET /inspections/{id}/artifacts/input` | 从后端私有存储读取方向校正 PNG |
| `GET /inspections/{id}/artifacts/result` | 从后端私有存储读取 AI 原始标注 PNG |

浏览器实际访问上述路径前添加 `/api`，Vite/Nginx 去掉该前缀。浏览器不直接请求 8001。AI 推理事件默认 private，只有操作员明确勾选时才发布通用“已保存图像复核记录”摘要；不公开内部备注或检测图。复核不修改 `batches.status`，不宣称整批合格。

A3 错误使用 `{code,message}`；其他接口保持原格式。记录 UUID 幂等键防止同一上传重试重复创建；跨浏览器/新选图会生成新的请求键。
