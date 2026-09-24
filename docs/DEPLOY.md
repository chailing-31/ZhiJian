# 部署与启动 v1.0

依赖 Docker Compose。初始提交执行 `docker compose up -d mysql`，首次创建数据卷时自动运行 `tables.sql` 和 `demo.sql`；通过 `docker compose ps` 查看健康状态。连接方式：本机 `localhost:3306`、库名 `smart_fresh_demo`，本地开发凭据见 `.env.example`，使用前复制为 `.env` 并修改；`.env` 不入库。

此时还不能执行完整的 `docker compose up -d` 启动四服务，因为三端的程序及 Dockerfile 尚未提交。首周 C 接入后端/前端，A 接入 AI 服务的占位健康接口，再扩充 Compose，联调验证首页、批次和手机公开时间线。模型真实推理为第二阶段验收。
