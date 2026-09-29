# 智检鲜达 Demo（ZhiJian）

AI-based fruit quality inspection and traceability demo system.

本项目围绕烟台苹果批次 `APPLE-2026-001`，逐步实现批次登记、视觉质检、加工规则建议、冷链告警、批次报告与扫码溯源。当前版本已打通批次创建、查询和公开溯源，其他环节仍待开发。

## 技术约定

- Vue 3：`frontend/`；Spring Boot（JDK 17）：`backend/`；Python FastAPI + YOLO：`ai-service/`；MySQL 8：`database/`。
- 浏览器只访问后端业务接口；后端访问 AI 服务并写入数据库。所有业务记录用内部 `batch_id` 外键关联；二维码使用可公开的 `batch_code` 查询。
- 加工建议首版是规则建议；模拟传感器数据须标记 `simulation`；未发生的事件不作为真实结果展示。

## 首次启动范围

执行 `docker compose up --build -d` 启动 MySQL、Spring Boot 和 Vue。MySQL 首次创建数据卷时自动运行 `database/tables.sql` 与 `database/demo.sql`。访问 `http://localhost:5173` 查看演示批次；`http://localhost:5173/trace/APPLE-2026-001` 查看公开时间线。当前完成的是批次流程骨架，质检、加工和冷链功能尚待接入。

## 协作

`main` 为稳定分支；`develop` 用于集成；`feature-ai`、`feature-business`、`feature-system` 分别由 A/B/C 开发。功能分支提交 PR，经至少另一名成员复核再合入 `develop`；稳定演示版本从 `develop` 提 PR 合入 `main`。A 负责 `ai-service/`，B 负责 `business/`，C 负责 `backend/`、`frontend/`、`database/`。共同维护 `docs/API.md`，变更字段时同步更新示例和 `docs/DATABASE.md`。

首周日程与验收见 [WEEK1.md](docs/WEEK1.md)。
