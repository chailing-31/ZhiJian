# 智检鲜达：六模块前端与集成状态 v0.3

本文件记录当前页面的真实接入状态。

| 页面 | 地址 | 当前行为 | 尚未完成 |
| --- | --- | --- | --- |
| 首页 | `/` | 真实批次列表与模块入口 | 统计项继续随业务扩展 |
| 批次管理 | `/batches` | 创建、搜索、列表、详情 | 更完整状态机 |
| AI 质检 | `/inspection?batch_id=...` | 真实上传、推理、持久化、人工复核、历史 | 权限、生产并发、正式评测 |
| 加工品控 | `/processing?batch_id=...` | 规则建议、人工修改采用值、保存与历史读取 | 正式规则参数与权限 |
| 冷链监测 | `/coldchain?batch_id=...` | CSV 导入、读数摘要、episode/告警和人工处置 | 实时设备接入与正式阈值 |
| 溯源与报告 | `/traceability?batch_id=...` | 公开时间线、二维码、综合记录入口 | 包装/运输/出厂录入 |
| 内部综合记录 | `/batches/:id/report` | I1：只读聚合质检/复核、加工、冷链、告警、事件 | 正式报告模板、权限、分页 |
| 公开溯源 | `/trace/:batchCode` | I2：公开时间线 + public_summary | 更多 public 事件需后续真实写入 |

“暂无记录”不等于“安全”“合格”或“未发生”。

## 已接入前端 API

```text
GET  /api/batches
POST /api/batches
GET  /api/batches/{id}

POST  /api/batches/{id}/inspections
GET   /api/batches/{id}/inspections
GET   /api/inspections/{id}
PATCH /api/inspections/{id}/review
GET   /api/inspections/{id}/artifacts/input
GET   /api/inspections/{id}/artifacts/result

POST /api/batches/{id}/processing-advice
GET  /api/batches/{id}/processing-records
POST /api/processing-records/{id}/adopt
POST /api/batches/{id}/sensor-readings
GET  /api/batches/{id}/coldchain
POST /api/alerts/{id}/resolve

GET /api/batches/{id}/report
GET /api/trace/{batch_code}
```

## 仍待完成

```text
包装 / 运输 / 出厂录入
```

## AI 页面

真实链路：

```text
浏览器选图
  ↓
Spring Boot
  ↓
FastAPI 8001
  ↓
YOLO
  ↓
证据图 + MySQL
  ↓
人工复核历史
```

人工复核不会覆盖模型原始结果。

## 报告与公开溯源

I1 `/batches/{id}/report`：只读聚合内部已保存数据。

I2 `/trace/{batch_code}`：只返回产品、品种、产地、public events 和只由这些 public events 计算的 `public_summary`。

公开页不返回内部：

```text
batch_id
supplier
model_version
confidence
候选框
人工内部备注
private event
内部证据路径
```

## 批次上下文

内部使用 `batch_id`，公开查询使用 `batch_code`，二者不能互换。

## 分工

- A：AI 已完成并冻结，后续主要提供服务支持和前端协助
- B：加工与冷链
- C：后端总集成、数据库公共逻辑、报告、溯源、部署
- C 的加工/冷链适配、I1 报告与 I2 公开溯源已实现；生产鉴权和部署加固仍未完成

## 本地前端

```bat
npm test
```

```bat
npm run dev
```

```bat
npm run build
```

Windows 默认 minify 偶发卡住时：

```bat
npm run build:safe
```

手机联调：

```bat
npm run dev:lan
```

完整启动见 `docs/DEPLOY.md`。
