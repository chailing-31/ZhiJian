# 部署与启动说明 v1.3

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
```

并已验证：AI 上传 → 持久化 → 人工复核 → 综合记录 → 公开溯源。

加工和冷链业务写入仍待 B 接入。

## 1. 当前推荐方式

Windows 本机开发推荐直接运行 MySQL + AI service + Spring Boot + Vue。

现有 `docker-compose.yml` 仍是早期基础版：

- 没有 AI service；
- 不会自动执行 A3 增量迁移；
- backend 也没有按完整 inspection profile 配置。

因此当前不要把 Docker Compose 当作完整 AI Demo 一键部署。

## 2. 数据库

```text
smart_fresh_demo
```

基础初始化：

```bat
cd /d D:\Projects\ZhiJian
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

## 3. AI 服务 8001

```bat
cd /d D:\Projects\ZhiJian\ai-service
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

需确认 `model_ready=true`。

## 4. Spring Boot 8080

```bat
cd /d D:\Projects\ZhiJian\backend
```

```bat
set "DB_USER=root"
```

```bat
set "DB_PASSWORD=<本机MySQL密码>"
```

```bat
run-inspection.cmd
```

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

如 8080 被占用：

```bat
netstat -ano | findstr :8080
```

确认旧 Java 进程后再停止，不要同时启动两个后端。

## 5. Vue 5173

```bat
cd /d D:\Projects\ZhiJian\frontend
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

## 6. 测试和构建

后端：

```bat
cd /d D:\Projects\ZhiJian\backend
```

```bat
mvn test
```

前端：

```bat
cd /d D:\Projects\ZhiJian\frontend
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

## 7. 当前验收

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
cd /d D:\Projects\ZhiJian
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
