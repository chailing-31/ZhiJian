# 部署与启动说明 v1.4

当前完整 Demo 已跑通：

```text
MySQL
  ↓
Spring Boot 8080
  ↓
Vue 5173

Spring Boot 8080
  ↓
AI Service 8001
  ↓
YOLO

Spring Boot 8080
  ↓
B Rule Service 8002
```

历史记录曾验证 AI 上传 → 持久化 → 人工复核 → 综合记录 → 公开溯源；本分支补入 A15 后，仍需在当前 C 环境重新执行真实图片验收。

加工建议/人工采用历史与冷链读数/episode/告警/处置已经通过真实本机 MySQL 联调。A15 权重和 manifest 已纳入仓库，但 C 环境仍需按 `docs/AI_HANDOFF_A15.md` 完成真实模型加载、图片上传、落库、复核和公开摘要验收。

## 1. 当前推荐方式

Windows 本机开发推荐直接运行 MySQL + B service + Spring Boot + Vue；需要视觉质检时再增加 AI service。

本目录的 `docker-compose.yml` 可启动 MySQL、backend 和 frontend，B 规则服务由成员 B 独立提供，但：

- 没有 AI service；
- 会在新数据卷执行基础、A3 与 C 迁移；已有数据卷仍需手动迁移；
- 未包含 AI service，backend 也没有按 inspection profile 配置；
- backend 通过 `BUSINESS_SERVICE_DOCKER_URL` 连接 B 服务，默认 `http://host.docker.internal:8002`。宿主机运行 B 服务时，需监听容器可访问的地址（例如可信开发环境中的 `0.0.0.0:8002`），不能只监听 `127.0.0.1`。

因此当前不要把 Docker Compose 当作完整 AI Demo 一键部署。

## 2. 数据库

```text
smart_fresh_demo
```

基础初始化：

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration
```

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\tables.sql
```

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\demo.sql
```

A3 迁移：

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\migrations\20261001_A3_inspection_integration.sql
```

C 迁移：

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\migrations\20261012_C_business_integration.sql
```

不要把密码写入仓库。推荐为应用创建仅拥有 `smart_fresh_demo` 数据读写权限的账号。

## 3. B 规则服务 8002

```bat
cd /d E:\code\ZhiJian-main\ZhiJian-develop\business
C:\Users\asus\.local\bin\python3.14.exe -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -q
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8002
```

验证：`curl.exe http://127.0.0.1:8002/health`。Python 3.12 是交付基线；本机亦已用 3.14.3 验证 183 项测试。

## 4. AI 服务 8001（可选，需权重）

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\ai-service
```

如默认 Python 环境可用：

```bat
run-cpu-local.cmd
```

若 Python 位置不同：

```bat
set "AI_PYTHON=<实际可用python.exe>"
```

若 manifest 位置不同：

```bat
set "AI_MODEL_MANIFEST=<实际model-manifest.json路径>"
```

再：

```bat
run-cpu-local.cmd
```

验证：

```bat
curl.exe --noproxy "*" http://127.0.0.1:8001/ready
```

需确认 `model_ready=true` 且版本为 `ssda-y8n-a15-multidomain-20261006-05c5329e`。这只证明模型已加载，不等于真实图片全链路或准确率认证。

## 5. Spring Boot 8080

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\backend
```

```bat
set "DB_USER=<项目数据库账号>"
```

```bat
set "DB_PASSWORD=<项目数据库密码>"
set "BUSINESS_SERVICE_BASE_URL=http://127.0.0.1:8002"
```

```bat
call mvnw.cmd spring-boot:run
```

视觉质检联调时再使用 `run-inspection.cmd` 并设置 AI/存储参数。`mvnw.cmd` 会使用项目固定的 Maven 3.9.15，其他电脑无需先全局安装 Maven。

验证：

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/health
```

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/inspection-service/ready
```

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/batches
```

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/business-service/ready
```

最后一项须同时显示 `database_ready=true`、`business_ready=true` 和 `ready=true`。

如 8080 被占用：

```bat
netstat -ano | findstr :8080
```

确认旧 Java 进程后再停止，不要同时启动两个后端。

## 6. Vue 5173

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\frontend
```

首次：

```bat
npm install
```

开发：

```bat
npm run dev
```

访问：

```text
http://127.0.0.1:5173/
http://127.0.0.1:5173/inspection
http://127.0.0.1:5173/traceability?batch_id=1
http://127.0.0.1:5173/batches/1/report
http://127.0.0.1:5173/trace/APPLE-2026-001
```

5173 已占用时，优先直接访问已有 Vite，不要重复启动。

## 7. 测试和构建

后端：

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\backend
```

```bat
mvnw.cmd test
```

前端：

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration\frontend
```

```bat
npm test
```

默认：

```bat
npm run build
```

部分 Windows 开发机若在模块转换后 minify 阶段长时间无响应：

```bat
npm run build:safe
```

`build:safe` 等价于 `vite build --minify=false`。它仍完成模块转换、chunk 生成与 dist 写入，仅不压缩 JS。

C 加工/冷链只读复验：

```bat
cd /d E:\code\ZhiJian-main\ZhiJian-feature-integration
python3.14 scripts\smoke_c_integration.py
```

成功时输出 `C INTEGRATION READY (read-only checks)`；该脚本不创建或修改业务记录。

## 8. 当前验收

AI：

```text
http://127.0.0.1:5173/inspection
```

至少确认上传、检测、复核、刷新历史都正常。

内部综合记录：

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/batches/1/report
```

```text
http://127.0.0.1:5173/batches/1/report
```

公开溯源：

```bat
curl.exe --noproxy "*" http://127.0.0.1:8080/trace/APPLE-2026-001
```

应包含 `public_summary`，且不返回内部 supplier、batch_id、模型信息或 private event。

## 8. 手机二维码

同一可信局域网：

```bat
npm run dev:lan
```

二维码目标使用电脑局域网 IP，例如：

```text
http://192.168.x.x:5173
```

不要为了扫码开放 MySQL 3306 或 AI 8001。

## 9. 安全边界

当前是开发 Demo：

- 管理端无正式鉴权；
- 8001 仅监听本机；
- MySQL 不对公网开放；
- `evaluation_status=not_evaluated`；
- 无框不等于正常；
- 单张图复核不等于整批合格；
- public 页面只展示明确公开事件。

## 10. I4 全量只读冒烟

MySQL、AI 8001、Spring Boot 8080 和 Vue 5173 全部启动后：

```bat
cd /d ^<项目目录^>\ZhiJian-feature-integration
```

```bat
python scripts\smoke_full_demo.py
```

脚本为只读验收，不执行 POST/PATCH，不会创建或修改业务数据。

当前验收内容：

```text
AI /health
AI /ready
Backend /health
Backend /inspection-service/ready
批次列表与数据库链路
批次详情
A3 质检历史接口
I1 内部综合记录
I2 公开溯源与隐私边界
Frontend 5173
```

全部必需检查通过时输出：

```text
FULL DEMO READY (for the checks above)
```

该结论仅代表工程链路可用于当前 Demo 联调，不是食品安全、模型性能或生产部署认证。
