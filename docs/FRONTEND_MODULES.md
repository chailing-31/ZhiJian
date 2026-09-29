# 智检鲜达：六模块前端骨架 v0.2

本说明是本次页面拆分和协作约定，不替代 `docs/DEPLOY.md`。它基于已核对的 `feature-batch-flow` 前端、`BatchController.java`、`docs/API.md` 和《Demo完整开发任务书》制作。本更新不修改后端、表结构、演示数据或数据库密码。

## 1. 本次完成什么

管理端具备六个一级入口：首页、批次管理、AI 质检、加工品控、冷链监测、溯源与报告；批次详情成为携带同一 `batch_id` 跳转的入口。公开扫码页为独立 H5，不套用管理端布局。

“有页面”不等于“业务已经完成”。本次保留现有批次链路，并为未接入功能提供明确状态和可替换的独立组件。没有额外数据库，没有新的运行服务，没有 Docker 或 WSL 要求。

| 页面 | 地址 | 本次行为 | 未接入边界 |
| --- | --- | --- | --- |
| 首页 | `/` | 查询真实批次列表并统计登记数量，展示模块入口 | 待复核数、告警数显示 `—`，不假装为 0 |
| 批次管理 | `/batches` | 新建、搜索、列表、详情跳转 | 不生成质检等后续事件 |
| 批次详情 | `/batches/:id` | 读基础信息和已保存事件，携带批次跳转 | 有某类事件不等于该阶段全部完成 |
| AI 质检 | `/inspection?batch_id=...` | 选择批次、本地图片预览、预留模型结果与复核区 | 不上传、不推理、不存复核结果 |
| 加工品控 | `/processing?batch_id=...` | 工艺输入草稿、建议值/采用值区域 | 草稿不保存；生成和采用按钮禁用 |
| 冷链监测 | `/coldchain?batch_id=...` | CSV 文件选择、曲线容器、告警及处置区 | 只显示所选文件名，不导入、不画假曲线、不触发告警 |
| 溯源与报告 | `/traceability?batch_id=...` | 读取公开时间线、前端本地生成二维码、打开记录预览 | 包装/运输/出厂录入、完整报告接口待接入 |
| 公开溯源 | `/trace/:batchCode` | 只调用公开溯源接口，展示允许公开的字段 | 不查询内部批次列表，不显示供应商，不提供管理菜单 |
| 记录预览 | `/batches/:id/report` | 从现有详情和事件生成可打印管理端预览 | 不是正式质检报告，不调用未实现的后端 `/report` 接口 |

管理员页面本身还没有鉴权，因此本包不能作为可公开部署的管理系统。公开页少展示字段，不等于管理 API 已受权限保护。

## 2. 目录与维护边界

```text
frontend/src/
  App.vue                       # 只保留 RouterView
  router/index.js               # 统一路由表：系统集成人维护
  layouts/AdminLayout.vue       # 导航 / 页面框架
  config/modules.js             # 模块入口与接入状态
  components/                   # 通用页头、批次选择、时间线、空状态
  composables/useAsyncData.js    # 加载 / 错误 / 取消旧请求
  utils/batch.js                # 批次 ID、时间、链接与状态工具
  api/
    http.js                     # /api 代理与统一错误处理
    batch.js                    # 已接入：批次列表 / 创建 / 详情
    trace.js                    # 已接入：公开时间线
    inspection.js               # 明确拒绝执行的待接入适配器
    processing.js
    coldchain.js
    report.js
    pending.js
  pages/
    Dashboard.vue
    batch/BatchList.vue
    batch/BatchDetail.vue
    inspection/Inspection.vue
    inspection/InspectionWorkspace.vue
    processing/Processing.vue
    processing/ProcessingWorkspace.vue
    coldchain/ColdChain.vue
    coldchain/ColdChainWorkspace.vue
    trace/Traceability.vue
    trace/TraceWorkspace.vue
    trace/PublicTrace.vue
    report/BatchReport.vue
    NotFound.vue
```

| 角色 | 主要改动区域 | 与系统集成人的交付约定 |
| --- | --- | --- |
| A：视觉算法（你） | `ai-service/`，对接 `pages/inspection/`、`api/inspection.js` | 推理函数或服务、输入输出样例、模型与证据图；原始结果与人工结果分别保存 |
| B：加工与冷链 | `business/`，对接 `pages/processing/`、`pages/coldchain/` 及对应 API 文件 | 规则与数据字段、建议依据、正常/异常样本、告警结果与处置所需字段 |
| C：系统集成；你可协助前端 | `backend/`、`database/`、公共框架、批次、溯源和报告 | Spring Boot 统一落库、事件生成、模块贯通及回归验收 |

A/B 可以在约定模块的页面内开发，但不是要求算法同学同时重写整套前端。通用导航、路由、公共样式、依赖清单和接口规范由指定集成人统一维护。新增模块 CSS 尽量写在自己的组件内并使用 `<style scoped>`；需要调整通用样式时先沟通。

不再将功能堆到 `App.vue`。也不要把多个独立前端、多个端口最终临时拼接。

## 3. 批次上下文与数据隔离

内部主键 `batch_id` 使用现有 API 返回的数值 ID；公开编号使用 `batch_code`。两者不互换，不能假设队友电脑里 `APPLE-2026-001` 的 ID 一定也是 1。

模块 URL 使用 `?batch_id=实际ID`。没有传入 ID 时，从该后端的真实列表和本浏览器的会话选择中决定初始批次。页面显示批次编号供用户核对。错误 ID 或不存在的批次显示错误，不静默切成另一个业务批次。

切换批次时取消上一请求，清除上一批次的数据和本地草稿；未返回新批次前不展示旧结果。AI 本地图片预览在离开页面时释放，不写入数据库。

现有事件列表仅按原始事件展示。流程覆盖区当前识别“入厂、AI质检、AI 质检、质检、人工复核、加工、冷链、仓储、冷链运输、包装、运输、出厂”等事件名称；这是展示映射，不是后端事件枚举或状态机。后续统一枚举时需同时修改显示映射与测试。未知事件仍在事件时间线原样显示，不据此推断阶段完成。

## 4. API 边界：不要照旧示例直接连 Python

当前运行代码和仓库 `docs/API.md` 已区分内部数值 `batch_id` 与公开字符串 `batch_code`。早期上传的 API v1.0 Word 示例有把 `APPLE-2026-001` 填入 `batch_id` 的情况，也有 `/inspection` 和 `/inspections` 等路径差异。它们不是当前已经实现的接口。这个包没有修改那些源文件，也没有自动补出缺失的后端接口。

**当前页面实际调用的只有：**

```text
GET  /api/batches
POST /api/batches
GET  /api/batches/{id}
GET  /api/trace/{batch_code}
```

`/api` 由 Vite 或现有 Nginx 代理去掉，再访问 Spring Boot。保留已有 `/health` 与冒烟脚本。浏览器不直接调用 MySQL、YOLO 或 Python 服务，不持有数据库口令。

**仓库 `docs/API.md` 已规划、但本次仍未实现的业务接口：**

```text
POST  /batches/{id}/inspections
PATCH /inspections/{id}/review
POST  /batches/{id}/processing-advice
POST  /batches/{id}/sensor-readings
POST  /alerts/{id}/resolve
GET   /batches/{id}/report
```

质检历史查询、建议采用值保存、冷链历史查询、告警列表以及包装/运输/出厂事件录入等返回结构尚未完整冻结。模块接入前由 A/B/C 确认，并更新 `docs/API.md`；不要将本包某个输入框直接当成最终接口字段，也不要自行假设单位或发明 URL。

`api/inspection.js` 等文件目前抛出 `FeatureNotReadyError`，不会返回空数组或假成功；未接入按钮禁用。不能只把 `config/modules.js` 的状态改为“已接入”而没有实现 API。

## 5. 每个模块怎样交付

先给出输入、输出、错误与空数据样例，再补 Spring Boot 调用与落库，再接模块 API 和页面，最后用同一批次回归。AI 服务不直连数据库。业务表与应写入的事件要由后端保持一致，失败时不能产生虚假的“完成”事件。

视觉质检验收：新图真实推理；保留模型版本、检测框、置信度、原图/结果图；人工改判不覆盖原始模型结果。

加工品控验收：输入参数、规则依据、建议值、采用值与操作员可追溯；初版明确 `advice_type=rule`，没有数据验证前不写“LSTM 已优化”。

冷链验收：时间戳、来源、正常/异常样本明确；阈值与持续时间有依据；告警出现、确认、处置都可查询。没有接入数据不能显示“0 告警 = 安全”。

溯源与报告验收：公开与内部字段分开；只展示保存的事件；完整报告需补齐相关表查询与证据路径，不能由前端随意合成结论。

## 6. 独立数据库与联调

本地仍是每个人一套测试库。Git 只同步代码、表结构脚本与演示数据，不同步运行中的 MySQL 数据。

联调环境使用一套共享测试库和统一后端；生产数据另行隔离。不要将三个人的本地测试库直接拼接，也不要让三个人分别手改共享库表结构。以后已有表的变更需要版本化迁移脚本；`git pull` 和 `CREATE TABLE IF NOT EXISTS` 不会自动修改旧表。

## 7. 开发启动与依赖

本次新增 `vue-router@4.5.1` 和 `qrcode@1.5.4`，保留 Vue `3.5.13`、插件 `5.2.1`；将 Vite 从原 `6.0.5` 调整到同主版本 `6.4.3`。包内没有伪造 `package-lock.json`，首次 `npm install` 会更新/生成锁文件，请检查并提交；队友以后使用已提交的锁文件。

默认 `npm run dev` 只监听本机并固定 5173；端口占用时直接报错，不自动跳到其他端口。手机联调使用 `npm run dev:lan`。生产部署不要使用 Vite 开发服务器。

```bat
cd /d D:\Projects\ZhiJian\frontend
```

```bat
npm install
```

```bat
npm run check:vue
```

```bat
npm test
```

```bat
npm run build
```

```bat
npm run dev
```

后端与数据库按已跑通的方式继续运行，不重建库。只有前端需要重启和重新安装依赖。不要把两条命令粘在同一行。

## 8. 手机二维码

先在同一可信局域网中运行 `npm run dev:lan`。在“溯源与报告”填写电脑当前的局域网 IP 和 5173 端口，例如 `http://192.168.1.6:5173`；这里的 IP 只是示例，不保证仍是你电脑的地址。手机不能使用电脑的 `localhost`。

二维码由浏览器本地生成，只编码公开查询链接，不包含供应商联系方式或数据库凭据。扫码不通时先确认手机能手动打开同一链接，再检查 Windows 私有网络防火墙规则。不要为扫码把 MySQL 3306 或未鉴权管理端暴露到公网。

## 9. 依据与技术参考

需求依据：《Demo完整开发任务书》第 1 页模块清单、第 3 页三人分工；当前 `feature-batch-flow` 中的 `BatchController.java`、`docs/API.md`、Vite/Nginx 配置。此处的组件拆分、路由及本地预览是本次工程实现方案，不声称原文已经包含这些细节。

技术参考：Vue Router 官方 `Getting Started` 与 `Different History modes`；Vite 官方 `Server Options`；Vite 安全公告 GHSA-fx2h-pf6j-xcff（其中列出 6.4.3 修复版本）；qrcode 项目 README。固定版本不等于永远不存在安全问题，发布前仍需依赖审计。

```text
https://router.vuejs.org/guide/
https://router.vuejs.org/guide/essentials/history-mode
https://vite.dev/config/server-options
https://github.com/vitejs/vite/security/advisories/GHSA-fx2h-pf6j-xcff
https://github.com/soldair/node-qrcode
https://docs.npmjs.com/cli/v11/using-npm/config/
```
