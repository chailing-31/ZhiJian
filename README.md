# 智检鲜达 Demo（ZhiJian）

面向果蔬批次的 AI 视觉质检、加工品控、冷链监测与公开溯源 Demo。

当前主要演示批次：

```text
APPLE-2026-001
```

## 当前真实状态

截至 `feature-integration` 的 I2 阶段，已经跑通：

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
```

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 首页 / 批次管理 | 已接入 | 真实 MySQL 批次数据 |
| AI 视觉质检 | 已接入 | FastAPI + YOLO + Spring Boot + MySQL + 人工复核 |
| 加工品控 | 待 B 接入 | 页面骨架和基础表已存在，业务接口尚未实现 |
| 冷链监测 | 待 B 接入 | 页面骨架和基础表已存在，读数/告警写入尚未实现 |
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
   └── AI Service 8001
           ↓
        best.pt
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

迁移：

```text
database/migrations/20261001_A3_inspection_integration.sql
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

GET /batches/{id}/report
GET /trace/{batch_code}
```

完整字段见 `docs/API.md`。

## Windows 本机启动

完整 Demo 推荐：

```text
1. MySQL
2. AI service 8001
3. Spring Boot 8080（inspection profile）
4. Vue 5173
```

详细命令见 `docs/DEPLOY.md`。

## 前端测试与构建

```bat
cd /d D:\Projects\ZhiJian\frontend
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

- 加工建议生成、采用值保存与历史查询
- 冷链读数写入、告警生成与处置
- 包装 / 运输 / 出厂录入
- 管理端登录、权限与审计
- 正式部署与公网安全加固
- 模型独立 test 与正式类别语义
