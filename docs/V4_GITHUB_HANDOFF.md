# V4 快照交接与本机启动

## 先读

这是候选集成分支，不是 main/develop 的直接覆盖。`business/` 来自 B 的固定提交，Java/Vue 来自安装后的 V4 工作目录。状态见 [阶段记录](releases/V4_20261013.md)。

运行旧目录的服务不受导出影响。该提交不需要在旧目录重新安装 V4 或重新执行数据库初始化。

克隆此分支后，直接使用仓库自带 `scripts/v4_local.py`，**不要运行旧补丁包的 install_v4.py 或依赖旧 Git HEAD 的 launcher.py**。不要用 `run-a15.cmd`；它会强制加载 A15。

## 环境和外部资产

保留 Java 17、Maven wrapper、Node 与 Python/FastAPI 的既有依赖；没有自动升级 torch/Ultralytics。B 的环境与 AI 的环境保持独立。新机器先按各服务 requirements 和锁文件准备环境。

旧模型 manifest 与权重需团队单独交接，并核验 SHA256：

```text
model_version = ssda-yolov8n-20261001-8c45a0602dad-n050-sem1
weights_sha256 = 8c45a0602dadf8b9f48b609e29d6d3b5e5868db5f68682da8b32583e104827a4
confidence_threshold = 0.25
iou_threshold = 0.50
image_size = 640
```

设置 `AI_PYTHON` 为原 AI 环境 python.exe，`B_PYTHON` 为 B 环境 python.exe，`ZJ_ACCEPTED_MANIFEST` 为上述 manifest 实际路径。入口也会尝试本仓库相应 `.venv`，AI 另兼容原 D 盘环境；找不到就停止，不下载或替换模型。

以下命令中的 `python` 应替换为本机可用的基础 Python（3.11 或更高）。模型由 AI_PYTHON 对应环境执行，不给基础 Python 安装 torch。

## 数据库

数据库仍为已存在的 `smart_fresh_c_review`。已有基础表、A3 和 C 表的机器，只需新增 Q4 表；你已通过 batch_workbench_ready=true 时不用再执行这一步。

在仓库根目录、Java 停止时：

```bat
python scripts\v4_local.py migrate
```

该命令明确确认后才执行 Q4 增量 SQL，不初始化、清空数据库，也不运行 `tables.sql` 或 `demo.sql`。全新数据库须按基础文档先准备；注意历史 SQL 中的 `USE smart_fresh_demo`，不要未经核对直接在现有环境批量执行。

## 四个服务，四个窗口

各窗口均位于**本快照仓库根目录**；不要启动后关闭窗口。同机已有相应服务时不要重复启动或直接结束不明进程。

```bat
python scripts\v4_local.py ai
```

```bat
python scripts\v4_local.py business
```

```bat
python scripts\v4_local.py backend
```

```bat
python scripts\v4_local.py web
```

对应端口为 A 8001、B 8002、Java 8080、Vue 5173。B 历史独立文档里的 8001 是独立演示地址，整机集成必须用 8002。Java 入口隐藏输入密码，不写入文件。

缺少前端依赖时，在 frontend 中执行 `npm ci --include=dev`。不要全局安装 Vite，不删除锁文件。

检查：

```bat
python scripts\v4_local.py status
```

浏览器在本机访问 `http://127.0.0.1:5173/batches`。进入批次后进行抽检、复核、批次评定和 B 工艺衔接。未认证的无鉴权管理端不得直接暴露公网。

## 重新验证与同事协作

在相应子目录执行后端 `mvnw.cmd test`，前端 `npm ci --include=dev`、`npm test`、`npm run check:vue`、`npm run build:safe`，AI 环境中执行 `python -m pytest -q`；B 使用自己的环境执行 `python -m pytest -q`。

完整链路检查：

```bat
python scripts\verify_batch_v4.py
```

测试夹具写入必须按脚本提示单独确认，不能将夹具当作真实操作。

完成提交后，向 develop 建立 **Draft Pull Request**，先审查冲突、复跑完整测试和业务验收。不要 force push；不要在未检查差异前合并 main/develop。
