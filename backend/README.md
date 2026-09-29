# Backend

Spring Boot 3.5 + JDK 17 + MySQL JDBC。实现 `GET /health`、`GET /batches`、`POST /batches`、`GET /batches/{id}` 与 `GET /trace/{batch_code}`。所有查询来自 MySQL；创建重复编号返回 409。公开溯源接口仅查询 `visibility=public` 的事件。

本地运行：先启动 MySQL，再设置 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`，执行 `mvn spring-boot:run`。容器运行由根目录 Compose 管理。API 示例见 `../docs/API.md`。
