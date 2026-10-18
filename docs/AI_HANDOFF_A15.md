# C 同学：A15 从仓库启动与验收

## 1. 先检查拉到的版本里有没有真实资产

在仓库根目录：

```bat
python ai-service\tools\verify_a15.py --require-tracked
```

必须输出 `A15_ASSET_VERIFIED`、`A15_ASSET_IN_GIT_INDEX`。该命令不需要 torch、Ultralytics 或网络。

目录应为：

```text
ai-service/models/a15/
  best.pt                 6,252,266 bytes
  model-manifest.json
  SHA256SUMS
```

不能只看到 `model-manifest.example.json`，也不能只提交启动脚本路径。若缺文件，请确认补齐 PR 已合入你拉取的分支；不能用空文件、随机权重、Git LFS 指针或通用 YOLO 权重替代。

## 2. 推理环境

A 已验证过的 Windows CPU 环境为 Python 3.13.13、torch 2.10.0+cpu、Ultralytics 8.4.51。可以继续使用已有合适环境，不必重装 A 已经运行正常的机器。**不要与 B 的独立 Python 环境混用。**

全新机器可在 `ai-service` 目录准备单独环境：

```bat
python --version
python -m venv .venv
.venv\Scripts\python.exe -m pip install torch==2.10.0 torchvision==0.25.0 --index-url https://download.pytorch.org/whl/cpu
.venv\Scripts\python.exe -m pip install -r requirements-service.txt "ultralytics==8.4.51"
.venv\Scripts\python.exe -m pip check
.venv\Scripts\python.exe -c "import torch, ultralytics, uvicorn; print(torch.__version__, ultralytics.__version__)"
```

上述 PyTorch/torchvision 对应关系见官方历史版本说明：https://pytorch.org/get-started/previous-versions/ 。不需要音频包 torchaudio。本次提供的是安装步骤，不声称已在 C 的机器安装成功；依赖下载失败要保留实际错误，不自动整体升级所有组件。

## 3. 启动 A15（Windows CMD）

在仓库 `ai-service` 目录：

```bat
run-a15.cmd
```

该入口显式使用当前仓库的 A15 manifest，不受以前遗留的 `AI_MODEL_MANIFEST` 影响。

`run-cpu-local.cmd` 仍保留环境变量覆盖，Python选择顺序是：显式 `AI_PYTHON`→本仓库 `.venv`→A 机旧的 D 盘环境（若存在）→PATH中的 python。模型默认是仓库相对路径，不再要求电脑上有 `D:\ZhiJianData\models`。

需要使用其他现成环境时，在同一 CMD 设置（可交互输入实际路径，不要填示例占位词）：

```bat
set /p "AI_PYTHON=Python executable full path: "
run-a15.cmd
```

输入内容不加外围引号。环境变量只在本机使用，不把个人路径/凭据提交。

新机器预测数据默认放 `ai-service/runtime/predictions`；若 A 机原 `D:\ZhiJianData\ai-service\predictions` 已存在则沿用，或由 `AI_STORAGE_ROOT` 显式指定。补齐包不移动或删除历史证据。`runtime/` 已被 AI 子目录的 .gitignore 忽略。

## 4. 验收分两级

另开 CMD，在仓库根目录：

```bat
python ai-service\tools\verify_a15.py --require-tracked --check-ready
```

必须看到 `A15_SERVICE_READY`，版本为 `ssda-y8n-a15-multidomain-20261006-05c5329e`。8001 已被占用时先确认现有进程归属，不要无差别终止所有 Python 进程。

这只完成文件和加载验证。下一步让浏览器经 Spring Boot 上传一张真实苹果图，检查：

1. 新 inspection 的 `prediction.model_version` 是上述 A15；有候选或无候选都如实保存。
2. input/result 证据可重新打开；不以伪造候选保证“成功”。
3. 人工复核单独保存，模型原始 JSON 不被覆盖；无目标不自动判 A。
4. 公共摘要只在操作员授权后发布；不泄露模型、内部备注、原图等数据。
5. B 加工/冷链服务仍使用独立8002；原 A 8001、后端8080、前端5173 不变。

C 自己的 MySQL 账号密码仍由 C 本机设置，不需要 A 的 root 密码。

## 5. 失败时查什么

- 文件缺失：确认 PR/分支和 `git ls-files ai-service/models/a15`，不要重新训练。
- 哈希不同：停止加载，核对包，不能改 manifest 哈希去迎合错误文件。
- 依赖缺失：确认启动时显示的 Python 和已安装依赖的 Python 是同一个。
- readiness仍是旧版本：确认监听进程和入口；使用 `run-a15.cmd`；不要凭端口在线判定版本正确。
- 低召回：属于模型已知限制，与“文件是否交付齐全”分开；保留人工复核，不修改现有冻结参数粉饰结果。

## 6. 仓库交付完成条件

PR必须同时包含 `best.pt` 本体、manifest、相对路径入口、模型卡和校验工具。PR合并后，C应在新工作区拉取并重新执行上述两级验收；在这之前不要把“已打包”写成“C真实AI全链路已通过”。
