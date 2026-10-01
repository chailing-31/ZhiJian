# A1：苹果视觉质检内部服务与接入约定 v0.1

## 1. 范围与当前状态

本次开始 A 负责的视觉模块，基于核对到的 `develop@66706cf5d80618b770b6a5783a4366f5655ec1c9`。原 `ai-service/` 仅有说明；前端 `api/inspection.js` 仍明确为待接入适配器。

本包仅新增 `ai-service/` 下的服务代码、测试、工具和本说明，不修改后端、前端、SQL、现有 README/DEPLOY 或密码。内部服务端口为 `127.0.0.1:8001`，与现有 8080/5173 分开。

**交付的是可执行服务和模型加载适配器，不包含已训练苹果权重、数据集或准确率结果。** 没有配置模型时服务进程正常，但 `/ready` 和推理接口返回 503；不会返回随机框、固定等级或“检测成功”。本包不会将前端模块状态改成“已接入”。

仓库 `docs/API.md` 的外部业务接口保持原样：浏览器调用 `POST /batches/{id}/inspections`，Spring Boot 调内部 Python 服务并负责落库。该外部业务接口、复核接口及页面绑定仍需与 C 对接，不在本包中实现。

## 2. 分阶段推进

| 阶段 | A 主要交付 | 验收含义 |
| --- | --- | --- |
| A1（本包） | 内部接口、上传验证、未就绪响应、证据存储、YOLO 适配器、测试 | 服务契约可运行；并非模型已训练或页面已接通 |
| A2 | 数据来源与标注清单、按果实/采集批次隔离的训练验证测试集、苹果基线权重与评测 | 新图片实际推理；公布真实测试指标及失败样例 |
| A3 | 与 C 对接上传、落库、图片访问、人工复核；补 AI 页面 | 单张图 → 模型观察 → 人工最终结果 → 批次事件贯通 |

本任务书的首版范围是苹果，优先覆盖正常样本、病斑与机械损伤；畸形、异物在数据确实支持时再增加。**示例 model-manifest 中的两类是接口填写示例，不是已经确认的数据类别、不是训练产物。** 必须先核对实际图片与标注，再决定缺陷区域检测、整果目标框和正常样本的标注方案。

没有框标注的分类目录不等于已具备缺陷检测框；苹果叶片病害图片也不能直接当作苹果果实缺陷训练集。计划中的图片数量和效果数字不能当作已完成证据。建议等级由经过确认的规则产生；本包没有擅自定义等级阈值，所以 `suggested_grade` 始终为空。

## 3. Windows 本机首次启动（不需要 Docker）

先按包内 APPLY.md 建立 `feature-ai`，应用新增文件。每条命令单独输入；报错即停止。

在新的 CMD 窗口进入：

```bat
cd /d D:\Projects\ZhiJian\ai-service
```

可先运行只读环境检查，不导入或安装 torch、不扫描整个硬盘：

```bat
python tools\preflight.py
```

安装本阶段轻量服务依赖：

```bat
setup-service.cmd
```

该脚本使用当前正常的 `python`（不使用此前失效的 `py`），在当前 D 盘项目的 `.venv` 建立独立环境；pip 缓存为 `D:\Caches\pip`，安装临时目录为 `D:\ZhiJianData\ai-service\tmp`。本阶段不安装 PyTorch/Ultralytics，不下载权重。依赖版本为制作环境测试过的顶层固定版本，并不是已在你电脑重新验证过的跨平台完整锁文件。安装失败不要擅自整体升级或重装 Node/MySQL。

启动服务：

```bat
run-service.cmd
```

此窗口保持运行。启动脚本只监听本机，默认 CPU、单 worker，不启用热重载。未配置模型时也会启动，便于区分“服务正常”和“模型可用”。

另开 CMD 检查：

```bat
curl.exe --noproxy "*" http://127.0.0.1:8001/health
```

无模型的预期响应：

```json
{"status":"ok","service":"zhijian-ai","model_ready":false,"model_status":"MODEL_NOT_CONFIGURED","model_version":null}
```

检查 readiness（此阶段预期 HTTP 503，不是前端故障）：

```bat
curl.exe --noproxy "*" -i http://127.0.0.1:8001/ready
```

服务契约冒烟：

```bat
python D:\Projects\ZhiJian\ai-service\tools\smoke_service.py
```

API 交互文档：`http://127.0.0.1:8001/docs`。它默认使用 FastAPI 的文档资源；离线无法显示文档页面时，可直接读取 `/openapi.json` 或随包 `openapi-a1.json`，不代表服务不可用。

当前只启动 A 的内部服务，不需要停 MySQL/Spring Boot/Vue，不会抢占 8080 或 5173。浏览器里的“AI 待接入”暂时保持原样是正确的。

## 4. 内部推理接口：给 C 的对接草案

下面为 A1 实现并自测的内部契约，供与 C 确认；不声称覆盖了仓库尚未冻结的全部业务字段。

```text
POST http://127.0.0.1:8001/ai/inspection/predict
Content-Type: multipart/form-data
```

| 输入字段 | 约束 |
| --- | --- |
| `image` | 单张 JPEG/PNG/WebP 文件；不接受 URL；不接受动画；最多 10 MiB、2400 万像素 |
| `batch_id` | Spring Boot 查询得到的真实数值 ID，正整数；不能传 APPLE-2026-001 |
| `batch_code` | 可选公开编号，字母/数字/横线，最多64字符；不能代替 batch_id |

整个请求正文上限 12 MiB，在 multipart 解析前按字节限制。文件内容实际解码校验，不仅看扩展名。服务不连数据库，不能核实 batch_id 是否存在或与 batch_code 匹配；**必须由 Spring Boot 校验并负责关联。** 浏览器不能自行指定另一个批次逃过后端权限检查。

成功响应字段以 `openapi-a1.json` 和 `PredictionResponse` 为准：

| 字段 | 语义 |
| --- | --- |
| `prediction_id` | 每次推理生成的 UUID；不是业务表 inspection_id |
| `batch_id` / `batch_code` | 回显输入，供业务后端再次核对 |
| `model_version` / `weights_sha256` | 操作员配置的版本、已核实文件哈希 |
| `evaluation_status` | 模型清单声明的评测状态；非系统自动认证的性能证明 |
| `executed_at` | ISO 8601 北京时间 +08:00 |
| `inference_ms` | 当前模型调用耗时，不含网络/图片落盘，不是吞吐或稳定性能指标 |
| `image` | EXIF 方向校正后的宽高、源文件 SHA-256、归一化 RGB 像素 SHA-256、坐标说明 |
| `detections[]` | `class_id`、`class_name`、中文 `class_label`、`confidence`、`bbox_xyxy` |
| `observation` | `target_defect_detected` 或 `no_target_defect_detected` |
| `suggested_grade` | 本阶段固定 null，尚无核定等级规则 |
| `grade_status` | `grading_rule_not_configured` |
| `requires_human_review` | 始终 true；没有目标框不等于正常或整批合格 |
| `artifacts` | 四个内部相对下载地址：source、input、result、record |
| `warnings` | 可见范围、人工复核与模型评测状态提醒 |

坐标统一为 **EXIF 方向校正后图像的像素 xyxy：左上 x1/y1、右下 x2/y2**，不是百分比或 xywh。C/前端应展示归一化 input 图或服务给出的 result 图，不能把原图的旋转方向和框坐标混用。结果图采用数字类别 ID 标注，中文标签从 JSON 展示，避免额外依赖字体。

原始上传文件存为 source；方向校正、透明像素白底合成的 input 图和 result 图存为 PNG，移除 EXIF 元数据。计算推理与像素哈希使用同一个归一化 RGB 图。每次保存 JSON、原文件和两份图后才返回成功，存储错误不伪装为成功。

错误行为：422 参数/图片非法，413 超出大小限制，503 模型未就绪或执行失败，507 证据写入失败，404 证据不存在。`/health` 返回 200 只能证明服务进程正常；`/ready` 成功也只说明模型已加载，不能代替新图片推理或准确率评测。

## 5. 证据文件与业务后端职责

默认保存到 `D:\ZhiJianData\ai-service\predictions\<prediction_id>\`。失败记录不会生成虚假完成事件。此服务没有业务数据库、批次状态机或复核记录。

C 对接时：先验证批次，调用内部服务，核对响应，将模型原结果写入 `inspections`；将要提供给浏览器的 input/result 文件下载到后端管理的文件存储或实现受控代理。不要把 Python 的磁盘路径或 8001 内网地址原样当成浏览器图片地址。

`GET /internal/artifacts/{prediction_id}/{source|input|result|record}` 只供后端取证；不要在公开溯源页直接暴露 source、内部 JSON 或所有存储目录。原始 source 可能仍含上传时的元数据。人工复核、最终等级、复核人、原因与时间由业务后端另行保存，不覆盖模型原始输出；何时生成哪种事件需要 C 与 A 确认，失败和待复核不得伪装成最终质检完成。

当前没有鉴权、用户隔离、并发资源控制和正式保留策略，只用于受控本机开发。**不要把 8001、MySQL 或未鉴权管理接口暴露到公网。** 数据目录需要单独备份和空间管理；本服务不自动删除证据。

## 6. 有了自己的苹果模型后怎样接入

先确认数据与训练设备，再确定匹配的 PyTorch/Ultralytics 版本和安装方式；不要因为启动 A1 就盲目安装大型 GPU 依赖。

模型可用后，把 `model-manifest.example.json` 复制到 D 盘项目外，修改为真实权重路径、SHA-256、类别 ID/名称、数据来源、模型版本与评测记录。示例 hash 全为0，故意不是可用权重；不要把它当真实模型配置。

只加载可信的自己训练/已核实来源权重。文件哈希只能核实文件是否符合清单，不能证明恶意权重安全或模型有效；不要尝试反序列化陌生 `.pt`。

服务先确认权重文件存在、格式为 `.pt`、哈希一致，再导入 YOLO，防止根据常见模型名自动下载。检测模型的 `task` 必须为 detect，类别 ID/名称必须与清单完全一致；通用模型会被不匹配的苹果清单拦截，不能把“识别到苹果”写成“识别病斑”。类别名可按真实数据配置，但不应靠改名把通用类别伪装成缺陷。

设置模型清单后重新启动：

```bat
set "AI_MODEL_MANIFEST=D:\ZhiJianData\models\apple_defects\model-manifest.json"
```

```bat
run-service.cmd
```

此步骤需要真实权重与完整模型依赖，本阶段先不执行。随后必须用正常与异常新样本验证预测、文件取回和模型原始结果，不只看 `/ready`。不采用缺陷数量临时编一个“合格率”或等级。

## 7. 验证与局限

制作环境的服务测试使用小型合成图片、内存 HTTP 客户端以及明确的测试替身；覆盖上传验证、未就绪、异常、坐标、EXIF、存储、证据路径和模型清单校验。测试替身只在 tests 中注入，生产命令没有 mock 模式开关。

实际测试项目数、运行版本和执行结果见包外 `VALIDATION.md`。**这些不是苹果模型评测、不是 Windows 本机验收、不是 Spring Boot/Vue 全链路接通证明。** 本环境未安装 Ultralytics，没有用户苹果权重；适配器的真实模型运行仍待 A2 验证。

本机自测时安装测试依赖，再执行：

```bat
.venv\Scripts\python.exe -m pip install --cache-dir "D:\Caches\pip" -r requirements-test.txt
```

```bat
.venv\Scripts\python.exe -m pytest -q
```

## 8. 数据准备清单（下一步需要确认）

目前未获得可核实的苹果图片/标注位置和苹果缺陷模型。需要确认的是：是否已有数据、图片是果实还是叶片、标签是分类还是框/掩码、许可和原始来源、是否能标识同一果实/采集批次、是否已有可信权重。

先核对一小部分样本与标签格式，再确定训练方案，不要求先上传整个数据集。数据与模型建议保存在 `D:\ZhiJianData\datasets\`、`D:\ZhiJianData\models\`，不推大文件到 Git。推理/训练环境分开；训练设备根据实际可用机器决定，不能假定这台华为笔记本有 NVIDIA GPU。

## 9. 依据

业务依据：《Demo完整开发任务书》3.2 视觉质检及验收边界；仓库 `develop@66706cf` 的 `ai-service/README.md`、`frontend/src/api/inspection.js`、`docs/API.md`。本内部服务字段细化与存储设计为本轮实现方案，不声称源文档已经冻结了所有字段。

技术依据：FastAPI 官方 Request Files、Request Forms and Files、Testing；Ultralytics 官方 Predict；Python 官方 venv。已核对接口写法，未据此宣称任何训练性能。

- https://fastapi.tiangolo.com/tutorial/request-files/
- https://fastapi.tiangolo.com/tutorial/request-forms-and-files/
- https://fastapi.tiangolo.com/tutorial/testing/
- https://docs.ultralytics.com/modes/predict/
- https://docs.python.org/3/library/venv.html
