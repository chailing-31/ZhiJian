# AI 视觉质检：A1 内部服务

完整说明见 `../docs/AI_SERVICE_A1.md`。本包新增内部 FastAPI 服务，不更改原 README，也不更改 Spring Boot、数据库和前端状态。

首次本机启动（Windows CMD，每条单独执行）：

```bat
cd /d D:\Projects\ZhiJian\ai-service
```

```bat
setup-service.cmd
```

```bat
run-service.cmd
```

访问 `http://127.0.0.1:8001/health`；没有自己的苹果模型时，`model_ready=false` 是预期状态。不要把服务启动成功叫作模型推理成功。

本服务不提供假结果，不自动下载模型，不给未检出图像判 A 级。真实模型加载适配器已写入 `inspection_service/engine.py`，但本包不含苹果权重。先确认数据与模型，再进入真实推理和前后端业务接入。

`setup-service.cmd` 只安装轻量服务依赖到 D 盘项目 `.venv`，不会安装 PyTorch 或 Ultralytics。缓存与临时目录默认放 D 盘。服务端口仅监听本机 8001，不改 8080/5173。

开发时以 `feature-ai` 为分支。新增接口字段是内部 A1 实现契约，需要和 C 确认；原有对外接口仍以 `docs/API.md` 为准。
