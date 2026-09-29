# 部署与启动 v1.1（批次流程）

依赖 Docker Compose。先将 `.env.example` 复制为 `.env` 并按需修改本地密码，再执行 `docker compose up --build -d`。首次创建 MySQL 数据卷时自动运行 `tables.sql` 与 `demo.sql`；通过 `docker compose ps` 检查容器状态。访问 `http://localhost:5173`，后端健康接口 `http://localhost:8080/health`。仓库提供的默认密码仅用于本地演示，不用于公网部署。

当前 Compose 启动 MySQL、后端和前端三个服务；AI 模型服务尚未接入。已有数据卷不会自动重跑初始化 SQL。可在 MySQL 容器中手动执行 `database/demo.sql` 幂等补建演示批次；不要用 `docker compose down -v` 清理含真实数据的卷。

手工验收：在首页看到至少一个批次，进入 `APPLE-2026-001` 详情，点击“查看手机溯源页”，仅见已记录的入厂事件。新建其他批次后首页数目增加，重复批次编号返回 409。自动 HTTP 检查见 `scripts/smoke_batch.py`。
