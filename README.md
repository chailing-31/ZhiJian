# 智检鲜达 Demo（ZhiJian）

AI-based fruit quality inspection and traceability demo system.

本项目围绕烟台苹果批次 `APPLE-2026-001`，逐步实现批次登记、视觉质检、加工规则建议、冷链告警、批次报告与扫码溯源。当前版本已打通批次创建、查询、详情、公开溯源和连续冷链检测；AI 图片检测与加工页面仍在接入。

## 1. 技术栈与目录

- 前端：Vue 3，目录 `frontend/`
- 后端：Spring Boot + JDK 17，目录 `backend/`
- AI 服务：Python FastAPI + YOLO，目录 `ai-service/`
- 数据库：MySQL 8，目录 `database/`
- 冒烟测试：`scripts/smoke_batch.py`

核心调用关系：

```text
Vue Frontend
    ↓
Spring Boot Backend
    ↓
MySQL

Spring Boot Backend
    ↓
AI Service（后续接入）
```

浏览器只访问后端业务接口；后端负责访问 AI 服务并写入数据库。业务记录统一通过内部 `batch_id` 关联，二维码和公开溯源使用 `batch_code` 查询。

## 2. 当前已跑通范围

当前批次 Demo 已验证以下链路：

```text
MySQL
  ↓
Spring Boot API
  ↓
Vue 首页
  ↓
批次详情
  ↓
公开溯源页
```

当前演示批次：

```text
APPLE-2026-001
```

已完成：

- 批次数据初始化
- 批次列表查询
- 批次详情查询
- 公开溯源时间线
- 前后端联通
- `smoke_batch.py` 冒烟测试
- 冷链单点/CSV上报、持续超温检测、历史保存、去重、恢复和人工处置；迁移及启动见 [连续冷链说明](docs/COLDCHAIN.md)

暂未接入：

- AI 图片质检
- 加工建议
- 完整批次报告

B 的规则与加工演示参数已整理到 [business/](business/README.md)，含模拟CSV、输入输出样例及测试。冷链规则已接入Java、数据库和页面；加工参数尚待业务核定，Java加工代理与采用记录仍待接入。现有数据库需先执行一次冷链迁移，不能仅更新页面。

2026-10-04：B新增 Isolation Forest 训练、校准、验证集选型、独立测试及模型推理接口。见 [算法说明](business/ANOMALY.md) 与 [实际实验结果](business/reports/ANOMALY_RESULTS.md)。规则基线仍保留；当前只有模拟实验且存在漏检，不代表完整业务已达标。

未发生或尚未接入的环节，不应在页面中展示为已完成。

## 3. 分支协作

分支约定：

- `main`：稳定版本
- `develop`：多人集成与联调
- `feature-*`：具体功能开发

当前批次流程位于：

```text
feature-batch-flow
```

在其合并到 `develop` 前，需要调试该流程的成员应切换到：

```bash
git switch feature-batch-flow
```

功能完成后提交 PR 至 `develop`，至少由另一名成员复核后合并。稳定演示版本再由 `develop` 合并至 `main`。

## 4. 数据库协作原则

开发阶段允许每位成员在自己的电脑上运行一个本地 `smart_fresh_demo` 数据库，例如：

```text
A 本地数据库
B 本地数据库
C 本地数据库
```

这些只是用于个人开发和调试的本地副本，并不代表最终系统需要三个数据库。

统一约定：

```text
开发阶段：每人本地数据库
        ↓
功能稳定后：1 套共享测试数据库
        ↓
最终部署：1 套正式数据库
```

数据库结构和演示数据统一通过以下文件维护：

```text
database/tables.sql
database/demo.sql
```

当数据库结构发生变化时，应同步更新 SQL 脚本和 `docs/DATABASE.md`，不要只修改某一位成员电脑上的数据库。

## 5. 启动方式

支持两种启动方式：

1. Windows 本机运行：MySQL + Maven + npm
2. Docker Compose

目前本机开发推荐直接使用本地环境运行；详细步骤见：

[docs/DEPLOY.md](docs/DEPLOY.md)

## 6. 快速验收

后端启动后：

```bat
curl.exe http://localhost:8080/health
curl.exe http://localhost:8080/batches
```

前端启动后访问：

```text
http://localhost:5173
http://localhost:5173/trace/APPLE-2026-001
```

在仓库根目录执行：

```bat
python scripts\smoke_batch.py
```

通过时应看到：

```text
Batch flow passed: list, detail, public trace, duplicate and missing batch
```

## 7. 开发分工

当前约定：

- A：`ai-service/`
- B：`business/`
- C：`backend/`、`frontend/`、`database/`

共同维护：

- `docs/API.md`
- `docs/DATABASE.md`
- `docs/DEPLOY.md`

接口字段、数据库字段或启动方式发生变化时，应同步更新对应文档。

首周日程与验收见 [docs/WEEK1.md](docs/WEEK1.md)。
