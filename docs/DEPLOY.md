# 部署与启动说明 v1.2

本文档用于帮助项目成员在本地复现并运行当前批次 Demo。

当前已跑通范围：

```text
MySQL → Spring Boot API → Vue 首页/批次详情/公开溯源页
```

AI 图片质检、加工建议和冷链告警暂未接入。

---

## 1. 获取代码

首次获取项目：

```bash
git clone https://github.com/chailing-31/ZhiJian.git
cd ZhiJian
```

当前批次流程仍位于：

```text
feature-batch-flow
```

因此在合并到 `develop` 前，需要执行：

```bash
git switch feature-batch-flow
```

如果本地还没有该分支：

```bash
git fetch origin
git switch -c feature-batch-flow --track origin/feature-batch-flow
```

后续该功能合并到 `develop` 后，团队统一使用：

```bash
git switch develop
git pull
```

---

## 2. 已验证开发环境

当前已验证环境：

- Windows 11
- JDK 17
- Maven 3.9+
- Node.js 24
- npm 11
- MySQL 8.0
- Python 3.x

检查命令：

```bat
java -version
mvn -version
node -v
npm -v
mysql --version
python --version
```

---

## 3. 数据库协作方式

开发阶段，每个成员可以在自己的电脑上维护一个本地：

```text
smart_fresh_demo
```

例如 A、B、C 各有一个本地开发数据库，这是正常的开发方式。

最终数据库规划：

```text
个人开发：本地数据库
        ↓
多人联调：1 套共享测试数据库
        ↓
正式部署：1 套正式数据库
```

数据库结构与基础演示数据必须通过 Git 中以下文件统一维护：

```text
database/tables.sql
database/demo.sql
```

不要只在本地手工修改表结构而不更新 SQL 文件。

---

## 4. Windows 本机运行

### 4.1 创建数据库

确认 MySQL 服务已经启动，然后登录：

```bat
mysql -u root -p
```

执行：

```sql
CREATE DATABASE IF NOT EXISTS smart_fresh_demo
DEFAULT CHARACTER SET utf8mb4;
exit
```

---

### 4.2 导入数据库结构和演示数据

在仓库根目录执行：

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\tables.sql
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo < database\demo.sql
```

进入数据库检查：

```bat
mysql --default-character-set=utf8mb4 -u root -p smart_fresh_demo
```

执行：

```sql
SHOW TABLES;

SELECT id, batch_code, product, variety, origin, supplier, status
FROM batches;

SELECT event_type, summary, visibility
FROM batch_events;
```

当前演示数据应至少包含：

```text
批次：APPLE-2026-001
产品：苹果
品种：红富士
产地：山东烟台
供应商：示例合作社
状态：created
```

并存在一条公开的入厂事件。

退出：

```sql
exit
```

---

### 4.3 配置并启动后端

后端配置文件：

```text
backend/src/main/resources/application.properties
```

默认数据库地址：

```text
localhost:3306/smart_fresh_demo
```

Windows CMD 中进入后端目录：

```bat
cd /d <项目路径>\backend
```

设置数据库账号：

```bat
set DB_USER=root
set DB_PASSWORD=<本机 MySQL 密码>
```

注意：以上两条命令必须分别执行，不要写在同一行。

启动：

```bat
mvn spring-boot:run
```

正常启动后，后端监听：

```text
http://localhost:8080
```

验证：

```bat
curl.exe http://localhost:8080/health
curl.exe http://localhost:8080/batches
```

健康接口应返回：

```json
{"status":"ok"}
```

批次接口应返回包含：

```text
APPLE-2026-001
```

---

### 4.4 启动前端

新开一个 CMD 窗口：

```bat
cd /d <项目路径>\frontend
npm install
npm run dev
```

正常情况下 Vite 会启动：

```text
http://localhost:5173
```

浏览器访问：

```text
http://localhost:5173
```

公开溯源页面：

```text
http://localhost:5173/trace/APPLE-2026-001
```

---

### 4.5 冒烟测试

保持 MySQL、后端服务正在运行，在仓库根目录执行：

```bat
python scripts\smoke_batch.py
```

当前 Windows 开发机推荐使用 `python`，如果本机 `py` 启动器配置异常，不必使用 `py`。

测试通过时输出：

```text
Batch flow passed: list, detail, public trace, duplicate and missing batch
```

这表示以下流程均通过：

- 批次列表
- 批次详情
- 公开溯源
- 重复批次处理
- 不存在批次处理

---

## 5. Docker Compose 启动

如果开发机已安装并正确配置 Docker Desktop，可使用 Docker Compose。

在仓库根目录：

```bat
copy .env.example .env
```

检查配置：

```bat
docker compose config
```

启动 MySQL：

```bat
docker compose up -d mysql
```

查看状态：

```bat
docker compose ps
```

首次创建 MySQL 数据卷时，会自动执行：

```text
database/tables.sql
database/demo.sql
```

然后启动应用：

```bat
docker compose up --build -d backend frontend
```

查看状态：

```bat
docker compose ps
```

验证：

```bat
curl.exe http://localhost:8080/health
curl.exe http://localhost:8080/batches
python scripts\smoke_batch.py
```

注意：已有 MySQL 数据卷不会自动重新执行初始化 SQL。不要随意对包含有效数据的环境执行：

```text
docker compose down -v
```

---

## 6. 当前验收标准

当前批次 Demo 验收通过需要满足：

1. MySQL 中存在 `APPLE-2026-001`
2. `/health` 返回正常
3. `/batches` 能返回批次数据
4. 首页能看到演示批次
5. 批次详情页可正常打开
6. 公开溯源页可正常打开
7. `python scripts\smoke_batch.py` 测试通过

当前只验收批次主链路。

以下功能尚未纳入本阶段验收：

- AI 图片检测
- 加工建议
- 冷链告警
- 完整批次报告

---

## 7. 常见问题

### 7.1 `/health` 正常，但 `/batches` 返回 500

优先检查数据库账号环境变量：

```bat
echo %DB_USER%
```

应只输出数据库用户名，例如：

```text
root
```

如果将两条 `set` 命令写在同一行，可能导致用户名被错误设置。

### 7.2 `demo.sql` 中文乱码

确保 SQL 文件使用 UTF-8 编码，并使用：

```bat
mysql --default-character-set=utf8mb4 ...
```

导入。

### 7.3 `py scripts\smoke_batch.py` 无法执行

如果 `python --version` 正常，直接使用：

```bat
python scripts\smoke_batch.py
```

---

## 8. 安全说明

- 不要把真实数据库密码提交到 Git
- `.env` 不应提交到仓库
- 本地开发可以临时使用 root，后续共享测试环境建议创建专用数据库用户
- 公网部署时必须重新设置数据库密码和服务配置
