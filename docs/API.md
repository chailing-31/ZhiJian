# API 契约 v1.0

2026-10-10：已接入连续冷链。Java提供原Word单点 `/coldchain/check`、数值ID单点上报、历史查询及原Word人工处置路径；Python继续使用内部历史数组协议。字段、事务、错误码和启动迁移见 [连续冷链接口说明](COLDCHAIN.md)。

2026-10-09 加工接口扩展约定：以《智检鲜达_Demo_API接口规范_v1.0》第五节为依据，`POST /business/process-advice` 支持字符串批次号并返回 `type=rule`；同时保留数值批次 ID 和 `advice_type` 供现有调用方使用。新增建议状态与规则依据，A/B 级可返回演示加工参数，C/REJECT 或超出适用范围时不给参数。完整字段与限制见 `BUSINESS_API.md`。此兼容扩展仅限加工接口，冷链接口仍接收数值 ID 与完整历史数组。

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
| `POST /coldchain/check` | 字符串batch_id、time、temperature、humidity；source可选 | 已实现；原Word单点请求，返回规则结果并持久化 |
| `POST /batches/{id}/sensor-readings` | timestamp、温湿度、source；单个采样点 | 已实现；写入与连续检测，重复去重 |
| `GET /batches/{id}/coldchain?source=simulation` | 内部ID、来源 | 已实现；readings、latest、alerts、server_time |
| `POST /alert/{id}/resolve` | operator、resolution | 已实现；原Word路径；/alerts/{id}/resolve也可用 |
| `GET /batches/{id}/report` | 内部 ID | 批次汇总；后续实现 |

固定验收样例：`GET /batches` 返回包含 `{"batch_id": 1, "batch_code": "APPLE-2026-001", "product": "苹果", "status": "created"}` 的数组；实际 `batch_id` 以数据库生成值为准。`GET /trace/APPLE-2026-001` 只显示 `入厂` 演示事件；其他阶段未发生则显示“暂无记录”。新建批次不会自动伪造后续事件。A 与 B 接入前各提交独立服务的请求/响应 JSON 示例。

## B 模块内部契约历史（2026-10-03）

本节保留历史迁移说明；当前加工兼容和连续冷链的已实现状态以上方更新及COLDCHAIN.md为准。

`business/` 已提供可独立运行的冷链规则与加工流程提示，内部接口为 `GET /health`、`POST /coldchain/check`、`POST /business/process-advice`。字段、来源、时间、样例和 Java 落库边界见 [BUSINESS_API.md](BUSINESS_API.md)，启动方式见 [business/README.md](../business/README.md)。这不改变上表对外接口的“后续实现”状态。

B 内部接口统一数值 `batch_id`、读数 `timestamp`、明确的 `source` 及 `advice_type=rule`。旧 Word/Fast 样例中的字符串 `batch_id`、`time`、`type` 不再适用；内部冷链输入为完整 `readings` 数组。前端继续访问 Spring Boot，由其验证批次、查询历史、调用 B 并落库。

2026-10-04：B服务升级0.3.0，新增内部 `POST /coldchain/analyze`，分别返回规则与已训练模型结果，原 `/coldchain/check` 保持兼容。模型未配置时503；训练、字段和来源标识见 BUSINESS_API.md。外部业务接口的未接入状态不变。

2026-10-04 算法优化：B服务0.4.0增加森林与正常邻域距离混合检测，模型结果新增algorithm枚举、分量分数与参考类型，详见 [BUSINESS_API.md](BUSINESS_API.md)。同条件留出对比见 [优化报告](../business/reports/OPTIMIZATION_V4.md)。Java、页面与落库仍待接入。
