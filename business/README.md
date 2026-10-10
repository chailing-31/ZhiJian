# Business：加工规则与冷链分析

B 的独立 Python 模块，版本 0.5.0。规则基线根据工作区 `Fast/app/__pycache__` 中保留的 Python 3.12 字节码重建；现已提供 Isolation Forest 与正常邻域距离混合检测、训练、校准、模型选择、测试及推理流程。原始 Fast 文件保留；本目录不依赖它或旧虚拟环境。迁移背景见 [MIGRATION.md](MIGRATION.md)。

## 当前交付

- 冷链：温度阈值 + 持续时间、数据中断、恢复与历史异常区段、输入校验。
- 加工：配置化的演示参数建议，A/B 返回 4h/6h 与 normal 压力档位；C、REJECT 和超出演示范围时不给参数并说明原因。兼容 Word v1.0 的字符串批次号与 `type=rule`，保留原有字段及演示/人工确认标记。具体范围、依据和调用见 [加工参数建议](PROCESSING.md)，尚非经核定的生产参数。
- FastAPI 内部接口、离线 CSV 分析、正常/超温/恢复三组模拟样本、请求响应 JSON 和自动测试。
- 算法：保留联合/分组 Isolation Forest，增加窗口/当前水平的正常邻域距离与混合检测；8/12/14项因果特征，独立正常校准、验证选型、多组留出测试、模型持久化和解释证据。最新 [优化对比](reports/OPTIMIZATION_V4.md) 在同一批全新模拟数据上比较新旧模型；运行见 [ANOMALY.md](ANOMALY.md)。模拟表现不等于真实效果或整套 B 任务已完成。

本模块无状态，不连接MySQL。连续冷链规则已通过Java接入数据库与页面，Java负责历史、去重和人工处置，见 [COLDCHAIN.md](../docs/COLDCHAIN.md)。加工Java代理和采用值保存仍待接入；模型分析接口仍为独立服务能力。

业务讨论入口：[规则与依据一览表](RULES_REVIEW.md)，列明公开参考条件、当前演示参数、等级流程提示、样本含义及待老师确认的事项。

完整交付要求见 [FULL_SCOPE.md](FULL_SCOPE.md)。规则版保留作为基线，不通过删去算法或加工任务来降低验收范围。

## 安装与验证

在仓库的 `business` 目录执行。使用 Python 3.12；若 `python` 指向其他版本，请替换为本机 Python 3.12 的可执行文件路径。无需激活虚拟环境，以下命令同时适用于 Windows CMD 和 PowerShell。

```bat
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -q
```

测试会为每次运行创建独立临时目录，并在退出时清理，避免普通CMD/PowerShell与沙箱账户共用pytest临时目录导致权限错误。无需管理员权限或手动删除系统临时目录；显式传入的`--basetemp`仍由调用者管理。

只运行规则基线可安装 `requirements.txt`；运行算法用 `requirements-ml.txt`；完整测试用 `requirements-dev.txt`。算法还需按 [ANOMALY.md](ANOMALY.md) 生成/训练受信的本地模型。规则依赖版本来自原 Fast 环境元数据，新增算法依赖单独列出；虚拟环境与编译缓存不提交 Git。

## 离线查看样本结果

以下命令不需要 MySQL、Spring Boot 或网络，也不会保存业务记录。`17` 仅为离线示例 ID；联调必须使用 `GET /batches` 返回的真实数值 ID。

```bat
.venv\Scripts\python.exe -m app.analyze_csv samples/normal.csv --batch-id 17
.venv\Scripts\python.exe -m app.analyze_csv samples/overheat.csv --batch-id 17 --config samples/config.json
.venv\Scripts\python.exe -m app.analyze_csv samples/recovery.csv --batch-id 17
```

| 样本 | 预期结果 |
| --- | --- |
| `normal.csv` | `status=normal`、`alert=false`、`episodes=[]` |
| `overheat.csv` | 10:01 开始超温，10:06 触发，`status=active`、`alert=true` |
| `recovery.csv` | 10:05 触发，10:06 恢复；最终 `alert=false`，但保留一段 `end_reason=recovered` 的历史异常 |

三组数据均为人工构造，采样间隔 1 分钟，`source=simulation`。门状态与电流只是输入记录，不用于推断异常原因。

## 启动内部服务

```bat
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

交互文档：<http://127.0.0.1:8001/docs>。另开终端，在 `business` 目录运行：

```bat
curl.exe http://127.0.0.1:8001/health
curl.exe -X POST http://127.0.0.1:8001/coldchain/check -H "Content-Type: application/json" --data-binary "@samples/coldchain-request.json"
curl.exe -X POST http://127.0.0.1:8001/business/process-advice -H "Content-Type: application/json" --data-binary "@samples/processing-request.json"
```

示例请求的 `batch_id=1` 不代表本机数据库存在该批次；独立规则服务不检查批次是否存在。C 必须在调用前查库验证。对应预期结果为 `samples/coldchain-response.json`、`samples/processing-response.json`。

## 规则语义与限制

默认配置为温度 **大于 8℃**、连续观测 **至少 5 分钟**、相邻采样间隔 **最多 2 分钟**；仅供演示，未经生产验证，须经指导老师确认业务依据。等于 8℃ 不算超温；等于 2 分钟的间隔仍视为连续。触发时间为第一次观测到持续时长达到要求的采样时间，不插值。持续时间依据时间戳计算，不依据点数。

每次传完整的、同一批次同一来源的分析序列（1—10000 条），严格按时间递增。时间必须含时区，输出统一 `+08:00`。不自动排序或去重；不接受空序列、重复时刻、混合来源或不合法字段。历史不足只返回 `insufficient_data` 或 `pending`，不能认为没有风险。

超过最大采样间隔时，旧异常以 `data_gap` 结束，新读数重新计时；数据中断不是恢复，也不是人工处置。规则 `alert` 只表示序列末尾是否正在告警，历史异常须读取 `episodes`。基线没有湿度/低温规则；新增 Isolation Forest 结果由 `/coldchain/analyze` 单独返回，不能和规则告警混为一项。本版尚无 LSTM。

## 给 C 的接入交接

完整字段与边界见 [BUSINESS_API.md](../docs/BUSINESS_API.md)。2026-10-10状态：

1. Java已提供Word单点冷链入口，验证批次后查询完整历史再调用Python，后者仍无状态。
2. 冷链读数、区段告警、快照与内部事件已事务保存并去重，超过10000点明确拒绝；长期续算尚待设计。
3. 观测恢复与人工处置已分开，冷链页面可查询、导入、回放和保存处置。
4. 加工Java代理、输入/建议/采用值保存及页面仍待接入；冷链事件默认内部可见，不自动公开。

B 后续：核定演示加工参数的实际业务适用性，配合 C 完成接入；完善真实数据验证。有合适连续工艺数据后再实现并评估 LSTM，此项不属于 Word API v1.0 的本轮 Demo 必补功能。完整项目缺口见 FULL_SCOPE.md，演示参数不等于工艺优化验证完成。
