# C 成员完成内容同步记录

日期：2026-10-12。来源：`../ZhiJian-develop/`；目标：本项目 `ZhiJian-feature-integration/`。

本次同步覆盖 C 已归档的负责范围：Java 后端、Vue 前端、数据库、API/数据库契约、前端和部署说明、系统联调脚本以及项目配置。按同步前比较新增 13 个文件、更新 22 个文件，72 个文件与来源一致。没有删除目标文件；`ai-service/`、`business/`、`demo-data/` 和 AI 专项文档保持原样。

被覆盖文件的原版本已逐文件备份并校验，备份目录：`../_backup_c_sync_20261012_001bdcfe/`（相对于本文件所在的 `docs/` 目录）。备份路径已加入忽略规则。

## 目标目录适配

同步后对 C 的配置做了以下适配：

- 目标没有 B 的服务代码。Compose 不再尝试构建 `./business`，改为通过 `BUSINESS_SERVICE_DOCKER_URL` 连接独立 B 服务，默认 `http://host.docker.internal:8002`；宿主机服务需监听容器可访问的地址。
- 本机运行后端仍使用 `BUSINESS_SERVICE_BASE_URL=http://127.0.0.1:8002`，两种运行方式使用不同环境变量。
- 部署说明中的 C 启动目录改为 `ZhiJian-feature-integration`；B 服务命令仍指向提供该服务的 `ZhiJian-develop/business`。
- 根 README 增加此次同步范围说明；其历史联调记录来自 develop，不表示此次重新进行了 MySQL 或完整服务联调。

未执行数据库迁移、数据库写入或服务重启；完整运行需要 B 规则服务，AI 质检另需 A 提供的权重和 manifest。

## 新增文件

- `backend/mvnw`
- `backend/mvnw.cmd`
- `backend/.mvn/wrapper/maven-wrapper.properties`
- `backend/src/main/java/com/zhijian/demo/business/BusinessIntegrationController.java`
- `backend/src/main/java/com/zhijian/demo/business/BusinessIntegrationErrors.java`
- `backend/src/main/java/com/zhijian/demo/business/BusinessIntegrationFault.java`
- `backend/src/main/java/com/zhijian/demo/business/BusinessIntegrationService.java`
- `backend/src/test/java/com/zhijian/demo/business/BusinessIntegrationServiceTest.java`
- `database/migrations/20261012_C_business_integration.sql`
- `frontend/src/utils/coldchain-csv.js`
- `frontend/tests/coldchain-csv.test.mjs`
- `scripts/init-local-database.ps1`
- `scripts/smoke_c_integration.py`

## 更新文件

- `.env.example`
- `docker-compose.yml`
- `backend/README.md`
- `backend/run-inspection.cmd`
- `backend/src/main/java/com/zhijian/demo/inspection/InspectionService.java`
- `backend/src/main/java/com/zhijian/demo/inspection/PredictionValidator.java`
- `backend/src/main/resources/application.properties`
- `backend/src/test/java/com/zhijian/demo/inspection/InspectionFlowTest.java`
- `docs/API.md`
- `docs/DATABASE.md`
- `docs/DEPLOY.md`
- `docs/FRONTEND_MODULES.md`
- `frontend/package.json`
- `frontend/src/api/coldchain.js`
- `frontend/src/api/processing.js`
- `frontend/src/config/modules.js`
- `frontend/src/pages/coldchain/ColdChainWorkspace.vue`
- `frontend/src/pages/inspection/InspectionWorkspace.vue`
- `frontend/src/pages/processing/ProcessingWorkspace.vue`
- `frontend/tests/api.test.mjs`
- `frontend/tests/batch-utils.test.mjs`
- `README.md`

## 验证

同步时已逐文件与来源做 SHA-256 校验；部署配置、目录说明和备份忽略规则随后按本目录情况调整。

本次同步后验证结果：

- 后端：20 项测试通过，0 失败、0 错误、0 跳过；使用本机 Maven 依赖缓存离线运行。
- 前端：当前 `npm test` 对应的 36 项测试全部通过。
- Vue：24 个组件通过编译检查，使用 develop 中已有的同版本编译器检查本目录源码，未复制依赖缓存。
- 未重新执行前端打包或完整 MySQL / AI / B 服务联调；测试中的 HTTP 替身及 H2 数据库不代表生产服务已就绪。

Git 提交以 `https://github.com/chailing-31/ZhiJian` 的 `feature-integration` 原有历史为基础（同步前提交 `428150e`）。本次只提交 C 范围的更新与同步记录；A / B 服务、模型权重、依赖缓存和本地备份不包含在提交中。
