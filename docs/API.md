# API 契约 v1.2（A3 + C 业务集成）

对外业务接口统一经 Spring Boot；AI 服务仅由后端调用；A3 质检接口在 `inspection` profile 下启用。业务内部主键为 `batch_id`（BIGINT），公开批次编号为 `batch_code`（如 `APPLE-2026-001`）。数据库 DATETIME 按北京时间解释，API 输出 ISO 8601 `+08:00` 时间。当前错误响应采用 Spring Boot 默认格式与 HTTP 状态码；统一错误结构待下一阶段冻结。

| 方法与路径 | 请求 | 响应/首周状态 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；已实现 |
| `GET /batches` | 无 | 批次数组，按内部 ID 倒序；已实现 |
| `POST /batches` | `batch_code`, `product`, `variety`, `origin`, `supplier` | `batch_id`, `batch_code`；已实现，重复编号返回 409，非法编号返回 400 |
| `GET /batches/{id}` | 内部 ID | 批次基础信息与全部事件；已实现，未找到返回 404 |
| `GET /trace/{batch_code}` | 公开编号 | I2：只返回产品、品种、产地、`visibility=public` 事件及仅由这些公开事件计算的 `public_summary`；不返回内部字段，未找到返回 404 |
| `POST /batches/{id}/inspections` | multipart `image` + `Idempotency-Key` UUID | A3 实现；保存原始预测和图片，返回质检记录。无自动等级 |
| `PATCH /inspections/{id}/review` | 版本号、逐候选标记、图像结论、复核人、说明 | A3 实现；追加复核版本，不改原预测。字段见 A3 接口说明 |
| `POST /batches/{id}/processing-advice` | `grade`, `temperature`, `humidity`, 可选 `material_temperature`, `operator` | C 调用 B 演示规则并保存输入及完整建议；已实现 |
| `GET /batches/{id}/processing-records` | 内部 ID | 最近 200 条已保存建议、采用值与操作员；已实现 |
| `POST /processing-records/{id}/adopt` | `operator`, `adopted_values`, 可选 `note` | 保存人工采用值；blocked 建议返回 409；已实现 |
| `POST /batches/{id}/sensor-readings` | 带时区时间、温湿度、`simulation/sensor`，可选门状态/电流/config | 幂等保存单点、组织同来源历史、调用 B 并保存 episode/告警；已实现 |
| `GET /batches/{id}/coldchain` | 内部 ID | 已保存摘要、最近 200 条读数、episode 和告警；已实现 |
| `POST /alerts/{id}/resolve` | `resolved_by`, `resolution` | 幂等保存人工处置和 private 事件；已实现 |
| `GET /business-service/ready` | 无 | `database_ready`, `business_ready`, `ready`；已实现 |
| `GET /batches/{id}/report` | 内部 ID | I1 已实现；只读聚合已保存的质检/复核、加工、冷链、告警与事件，不推导整批合格结论 |

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

## C 加工与冷链接口约定

先执行 `database/migrations/20261012_C_business_integration.sql`，并设置 `BUSINESS_SERVICE_BASE_URL`（本机默认 `http://127.0.0.1:8002`）。浏览器只调用 Spring Boot `/api`，不能直接调用 B。

冷链单点时间必须为带时区、精确到秒的 ISO 8601，例如 `2026-10-12T10:00:00+08:00`。同一 `batch_id + source + timestamp` 内容相同视为安全重试；内容不同返回 409，不覆盖旧点。Spring Boot 在事务内保存读数、调用 B、更新 episode/告警；B 失败时本次新读数回滚。`source=simulation` 始终表示模拟数据，不能展示成传感器实测。

episode 的 `ongoing/recovered/data_gap` 是规则区段状态；人工处置只改变对应 alert 状态，不能改写 episode 历史。恢复后 alert 保留演示级别 `HIGH`，状态变为 `observed`；这不是食品安全等级。B 的演示阈值与工艺建议均需人工确认。
