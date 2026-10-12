# 智检鲜达 Demo（ZhiJian）

面向果蔬批次的 AI 视觉质检、加工品控、冷链监测与公开溯源 Demo。

2026-10-12：已从 `ZhiJian-develop` 同步 C 的后端、前端、数据库及部署联调成果。本目录的 A / B 模块保留同步前版本；加工与冷链需要另行启动 B 提供的规则服务。同步范围与验证结果见 `docs/C_SYNC.md`。

当前主要演示批次：

```text
APPLE-2026-001
```

## 当前真实状态

截至 2026-10-12 的 C 集成阶段，已经跑通：

```text
批次登记 / 查询
    ↓
AI 图片质检（YOLO）
    ↓
Spring Boot 持久化
    ↓
人工复核与历史记录
    ↓
内部批次综合记录
    ↓
二维码 / 公开溯源
    ↓
公开事件摘要

加工输入 → B 规则服务 → MySQL 建议/人工采用历史
冷链 CSV → Spring Boot 去重 → B 连续规则 → episode/告警/人工处置
```

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 首页 / 批次管理 | 已接入 | 真实 MySQL 批次数据 |
| AI 视觉质检 | 代码已接入，权重待提供 | FastAPI + Spring Boot + MySQL + 人工复核；当前仓库没有 `best.pt` 和正式 manifest，不能宣称模型就绪 |
| 加工品控 | 已接入 | B 演示规则、建议保存、人工采用值及历史读取 |
| 冷链监测 | 已接入 | CSV 校验、逐点持久化、连续规则、幂等 episode、告警和人工处置 |
| 内部综合记录 | 已接入 | `GET /batches/{id}/report` 只读聚合已保存数据 |
| 公开溯源 | 已接入基础版 | 仅公开 `visibility=public` 事件，并提供公开摘要 |
| 正式部署 / 权限 | 未完成 | 当前仍是受控开发 Demo |

未发生、未保存或未公开的环节，不会被页面自动补成“已完成”。

## 技术栈

- 前端：Vue 3 + Vite
- 后端：Spring Boot 3 + JDK 17
- 数据库：MySQL 8
- AI 服务：Python FastAPI + Ultralytics YOLO
- 二维码：浏览器本地生成

```text
Browser
   ↓
Vue 5173
   ↓ /api
Spring Boot 8080
   ├── MySQL 3306
   ├── AI Service 8001（需要受信权重）
           ↓
        best.pt
   └── B Rule Service 8002
```

浏览器不直接访问 MySQL 或 8001。

## AI 模块

当前 Demo 冻结：

```text
confidence_threshold = 0.25
iou_threshold = 0.50
image_size = 640
evaluation_status = not_evaluated
```

详细说明见：

- `docs/AI_SERVICE_A1.md`
- `docs/AI_INTEGRATION_A3.md`
- `docs/AI_MODEL_FREEZE.md`

## 数据库

基础表：

```text
batches
inspections
processing_records
sensor_readings
alerts
batch_events
```

A3 增量表：

```text
inspection_payloads
inspection_reviews
```

C 增量表：

```text
coldchain_episode_records
c_integration_metadata
```

迁移：

```text
database/migrations/20261001_A3_inspection_integration.sql
database/migrations/20261012_C_business_integration.sql
```

## 当前主要接口

```text
GET  /health
GET  /batches
POST /batches
GET  /batches/{id}

POST  /batches/{id}/inspections
GET   /batches/{id}/inspections
GET   /inspections/{id}
PATCH /inspections/{id}/review

POST /batches/{id}/processing-advice
GET  /batches/{id}/processing-records
POST /processing-records/{id}/adopt
POST /batches/{id}/sensor-readings
GET  /batches/{id}/coldchain
POST /alerts/{id}/resolve
GET  /business-service/ready

GET /batches/{id}/report
GET /trace/{batch_code}
```

完整字段见 `docs/API.md`。

## Windows 本机启动

完整 Demo 推荐：

```text
1. MySQL 3306，并依次执行基础脚本、A3 和 C migration
2. B rule service 8002
3. Spring Boot 8080
4. Vue 5173
5. 需要演示视觉质检时再启动 AI service 8001；必须先取得受信权重和 manifest
```

详细命令见 `docs/DEPLOY.md`。

## 前端测试与构建

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\frontend
```

```bat
npm test
```

默认构建：

```bat
npm run build
```

部分 Windows 开发机若默认 minify 阶段偶发卡住：

```bat
npm run build:safe
```

`build:safe` 使用同一 Vite 和源码，只关闭最终 JS minify，适合本地验收；正式发布仍优先使用默认 build。

## 分支协作

- `main`：最终稳定版本
- `develop`：团队集成基线
- `feature-ai`：AI 模块已完成
- `feature-business`：B 的加工/冷链
- `feature-integration`：当前报告、溯源与系统联调准备

## 当前未完成

- 包装 / 运输 / 出厂录入
- 管理端登录、权限与审计
- 正式部署与公网安全加固
- 模型独立 test 与正式类别语义

## 已验证结果（2026-10-12）

```text
Backend: 20 tests passed
Business service: 183 tests passed (1 dependency deprecation warning)
Frontend: 36 tests passed; 24 Vue SFC passed; build:safe passed
Real local integration: MySQL + B 8002 + Backend 8080 passed
```

真实联调覆盖加工建议/采用历史、7 条冷链读数、持续超温 episode、恢复、人工处置和综合报告。该结果只证明工程链路，不证明规则为生产标准或食品安全结论。

C 链路只读复验（服务全部启动后）：

```bat
python3.14 scripts\smoke_c_integration.py
```

成功输出 `C INTEGRATION READY (read-only checks)`。

## I4 全量只读冒烟验收

完整服务启动后，在仓库根目录执行：

```bat
python scripts\smoke_full_demo.py
```

该脚本只发 GET 请求，不创建批次、不上传图片、不修改数据库，也不改变人工复核或公开状态。

当前检查：

```text
AI 8001 /health
AI 8001 /ready
Backend 8080 /health
Backend /inspection-service/ready
GET /batches
GET /batches/{id}
GET /batches/{id}/inspections
GET /batches/{id}/report
GET /trace/{batch_code}
公开响应隐私字段检查
Frontend 5173
```

全部必需项通过时输出：

```text
FULL DEMO READY
```

这只表示当前工程链路冒烟通过，不代表模型准确率、食品安全或整批质量认证。
