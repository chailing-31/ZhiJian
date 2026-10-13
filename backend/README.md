# Backend

Spring Boot 3.5 + JDK 17 + MySQL JDBC。实现 `GET /health`、`GET /batches`、`POST /batches`、`GET /batches/{id}` 与 `GET /trace/{batch_code}`。所有查询来自 MySQL；创建重复编号返回 409。公开溯源接口仅查询 `visibility=public` 的事件。

本地运行：先启动 MySQL 与 B 规则服务，再设置 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/BUSINESS_SERVICE_BASE_URL`，执行 `mvnw.cmd spring-boot:run`。Wrapper 固定 Maven 3.9.15；只需 JDK 17+。视觉质检另用 `run-inspection.cmd` 并提供受信 AI 权重。容器运行由根目录 Compose 管理。API 示例见 `../docs/API.md`。
