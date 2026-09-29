<<<<<<< HEAD
# 智检鲜达 Demo

AI-based fruit quality inspection and traceability demo system.

本项目围绕烟台苹果批次 `APPLE-2026-001`，实现批次登记、视觉质检、加工规则建议、冷链告警、批次报告与扫码溯源。管理端六页，手机端一页。当前提交是**项目结构和接口契约**，尚无应用代码。

## 技术约定

- Vue 3：`frontend/`；Spring Boot（JDK 17）：`backend/`；Python FastAPI + YOLO：`ai-service/`；MySQL 8：`database/`。
- 浏览器只访问后端业务接口；后端访问 AI 服务并写入数据库。所有业务记录用内部 `batch_id` 外键关联；二维码使用可公开的 `batch_code` 查询。
- 加工建议首版是规则建议；模拟传感器数据须标记 `simulation`；未发生的事件不作为真实结果展示。

## 首次启动范围

此提交仅可启动数据库：`docker compose up -d mysql`。执行 `database/tables.sql` 建表，执行 `database/demo.sql` 创建演示批次。应用服务 Dockerfile、实际接口和页面在首周各功能分支完成后加入，届时更新 Compose 与部署文档。不要把当前骨架称为“空系统已跑通”。

## 协作

`main` 为稳定分支；`develop` 用于集成；`feature-ai`、`feature-business`、`feature-system` 分别由 A/B/C 开发。功能分支提交 PR，经至少另一名成员复核再合入 `develop`；稳定演示版本从 `develop` 提 PR 合入 `main`。A 负责 `ai-service/`，B 负责 `business/`，C 负责 `backend/`、`frontend/`、`database/`。共同维护 `docs/API.md`，变更字段时同步更新示例和 `docs/DATABASE.md`。

首周日程与验收见 [WEEK1.md](docs/WEEK1.md)。
=======
# ZhiJian
智检鲜达——面向山东果蔬加工产业的AI视觉质检与全链路品控溯源系统
>>>>>>> 40fe3a6bd963f1e0deb55a980ce390b2ca293ae7
