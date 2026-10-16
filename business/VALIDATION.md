# B 模块验证记录

## B 独立功能与接口交付（2026-10-10，0.5.0）

- 完整回归 **183 passed，1 warning，63.49秒**。新增17项验收覆盖逐点触发/恢复/断点、HTTP前缀组织、失败即停、版本/结果不一致拒绝、非法CSV整体校验、UTF-8报告、禁止覆盖文件、加工配置故障503和OpenAPI与实现一致性。警告为既有Starlette/httpx弃用提示。
- 新增 `app.demo`：加工请求计算、冷链CSV逐点回放，可离线或直接调用B HTTP并核验结果；不依赖系统目录，不写数据库。补 `gap.csv` 断点样例。
- 加工配置缺失/错误现返回503，不提供备用参数，也不影响独立冷链规则接口。
- 独立HTTP验收：复制business至独立目录（不含虚拟环境、模型产物、系统目录），使用已有Python依赖启动真实Uvicorn；7组加工样例、4组冷链逐点HTTP回放、加工Word请求CLI、错误422/503以及实时OpenAPI一致性全部通过，共5组检查。验收服务已停止。此验证证明业务代码可独立运行，未声称从空白机器重新联网安装依赖。
- 本机复核记录：仓库 `.runtime/latest-b-delivery.json`，对应结果与日志目录 `.runtime/b-delivery-b7b84a1f`；本地日志不提交。接收方可用 [DELIVERY.md](DELIVERY.md) 的命令独立重现。
- 交接入口已收敛到本目录 [API.md](API.md)、[openapi.json](openapi.json) 与 [DELIVERY.md](DELIVERY.md)，不依赖根docs/scripts。原Word冷链单点请求仍由C转换为B历史数组，文档明确差异，未伪称Python可直接接原单点请求。
- 本轮没有修改Java、页面或SQL，没有提交/推送。未重新训练模型、核定生产参数或进行真实传感器效果评估；这些与已完成的B演示功能分别报告。

## 连续冷链系统联调（2026-10-10）

以下为此前本地系统集成的历史记录，不属于本轮B独立交付验收。原Word单点接口通过Java接入Python规则及MySQL，完成9组真实数据库场景验证，包括并发、重启、去重、恢复和事务回滚。前端36项测试与生产构建通过，Python当时为166 passed、1 warning（27.40秒）。浏览器验证曲线和人工处置保存；未进行生产迁移或真实传感器效果评估。B接收方无需这些系统文件即可按上节验收。

## 加工参数建议验证（2026-10-09，0.5.0）

- 新增 `demo-process-v3` 配置化演示规则：A/B 的预冷时长和压力档位、适用范围判断、C 人工复核、REJECT 暂停提示；所有结果保留演示及人工确认标记。
- 加工接口兼容 Word v1.0 的字符串批次号和 `type=rule`，保留数值 ID 与 `advice_type`。冷链接口仍使用原有历史数组与数值 ID。
- 完整回归：**166 passed，1 warning，31.43秒**，比此前新增42个用例。覆盖原文档请求、标识类型和非法值、区间端点、多个不适用原因、拒收优先级、跨请求隔离、缺字段、配置一致性及七组保存样例；既有冷链和模型测试通过。
- 警告仍为 Starlette TestClient 的 httpx 弃用提示，未隐藏或变更依赖。API 测试通过 TestClient 运行实际路由，不替代 Java/MySQL/Vue 部署联调。
- A级4h及适用区间为人为演示设定，B级6h/normal沿用接口文档样例；normal是档位标签而非压力数值。未核定生产适用性，未改变冷链阈值、重新训练模型或实现页面及采用值落库。

## 输入校验修复验证（2026-10-08）

- 修复数值字段将布尔值当作0/1的问题，覆盖传感器读数、规则配置和加工输入；CSV数值字符串仍可读取。
- 非有限数值及嵌套非法输入返回可序列化的422 `detail` 列表；时间转换为+08:00溢出时返回422。
- 完整回归：**124 passed，1 warning，24.43秒**。新增34个用例，覆盖布尔温度不能解除超温告警、三条业务接口的非有限数值、嵌套错误输入及时间上下界溢出。既有样本和算法测试继续通过。
- 警告仍为Starlette TestClient的httpx弃用提示；未调整依赖。此次不重新训练模型，不改变业务阈值或既有实验结论，未验证Java/MySQL/Vue联调。

## 最新算法优化验证（2026-10-04，0.4.0）

- 普通CMD运行兼容修复：发现系统TEMP下的pytest共享目录归沙箱账户所有，ACL不允许普通账户访问。新增conftest.py，为每次测试创建独立临时目录并自动清理，保留显式--basetemp选择。将PYTEST_DEBUG_TEMPROOT故意指向不可用的非目录路径，完整测试仍为90 passed、1 warning（52.38秒），验证不再依赖该共享根目录；用户普通CMD中的重跑结果需以其实际输出为准。

- 完整回归：**90 passed，1 warning**。新增正常近邻距离、单通道正反向偏移、零IQR、三点确认重置/因果性、当前水平分支、事件覆盖选型、多校准目标约束、配对序列统计与新API字段验证。
- 三组全新模拟测试，新旧模型使用同一正常训练/校准集及同一测试点。原模型召回40.56%、误报2.23%、事件22/45；优化模型召回97.72%、误报2.69%、事件45/45。选择结果在读入新测试CSV前冻结；完整证据见 [优化报告](reports/OPTIMIZATION_V4.md)。
- 加载实际模型novelty-bdab3d1c9fda5a51调用/coldchain/analyze返回200，规则告警与模型分开；输入输出保存于samples/anomaly-v4-request.json、samples/anomaly-v4-response.json。重新训练的对比模型分数与历史第三轮模型产物一致。
- 287个模型误报中225个发生于注入异常恢复后5分钟内，仍全额统计；未修改标签或进行整段点修正。剩余41个漏检点来自缓慢升温早期。
- 以上程序及模拟验证均不替代真实传感器评估；加工数值规则、LSTM及C系统联调缺口未消除。

## 历史算法扩展验证（2026-10-04，0.3.0）

- 服务版本0.3.0。完整测试：**71 passed，1 warning**；同样存在 Starlette TestClient 的 httpx 弃用提示，未隐藏。
- 原规则和CSV测试继续通过；新增因果窗口、缺测/设备缺字段、联合/分组模型、独立校准、模型持久化/校验、来源差异、模型缺失503、模型选择不读取测试数据等测试。
- 额外加载本轮实际训练的 `iforest-cb48e44223a72856`，通过 TestClient 调用 `/coldchain/analyze` 返回200，规则告警为true、模型评分12个窗口；请求与实际响应保存于 `samples/anomaly-request.json`、`samples/anomaly-response.json`。
- `pip check` 依赖一致性检查与文档链接检查通过。
- 三轮模拟实验和失败结果已保留，见 [实验结果](reports/ANOMALY_RESULTS.md)。第三轮模型点召回率约40.17%、误报率约2.25%，事件检出7/15；算法效果尚不达标，测试通过仅说明程序行为符合用例。
- 尚未验证真实传感器效果、网络部署或 Spring Boot/MySQL/Vue 联调；加工数值规则与LSTM尚未完成。

## 基线迁移历史记录（2026-10-03）

以下记录对应迁移时的0.2.0基础模块；后续算法进展以上方最新记录为准。

## 环境

- Windows，Python 3.12.14，项目独立 `.venv`。
- FastAPI 0.141.1、Pydantic 2.13.5、Uvicorn 0.54.0。
- pytest 9.1.1、httpx 0.28.1；本次安装解析到 Starlette 1.7.0。
- 安装文件：`requirements-dev.txt`；`pip check` 返回 `No broken requirements found.`。

## 自动测试

在 `business` 目录运行：

```bat
.venv\Scripts\python.exe -m pytest -q
```

本次结果：**53 passed，1 warning**。警告来自 Starlette TestClient 对 httpx 的弃用提示，未影响本轮结果；未隐藏该警告。

覆盖健康接口、数值批次 ID、边界阈值、持续时长、非等间隔采样、短时超温、重复计算、恢复、数据缺口、多段异常、跨请求无状态、时间乱序/重复/等价时区、显式时区、来源隔离、有限数值、湿度/电流范围、空/超长输入、四种等级提示、JSON 输出样例与 CSV 格式错误。

HTTP 测试使用 FastAPI TestClient 调用实际路由、校验与规则，无网络监听、不替代部署环境端到端测试。

## 命令行样本

通过新进程执行 `python -m app.analyze_csv`，指定 `--batch-id 17 --config samples/config.json`。

| 样本 | 实际结果 |
| --- | --- |
| normal.csv | batch_id=17，normal，alert=false，0 个异常段 |
| overheat.csv | active，alert=true，1 个 ongoing 异常段；triggered_at=2026-10-03T10:06:00+08:00 |
| recovery.csv | normal，alert=false，保留 1 个 recovered 异常段；triggered_at=2026-10-03T10:05:00+08:00 |
| normal.csv + batch_id=0 | 拒绝分析，退出码 2 |

数据为人工构造的模拟值，只证明当前演示规则按预期工作，不代表真实冷链检测准确率或业务阈值有效性。

## 基线迁移时尚未验证 / 尚未实现

- Java 对 B 的网络调用、MySQL 事务与告警去重、公开事件写入、Vue 交互和手机溯源。
- CSV 网页上传、定速播放、采用值保存、人工告警处置。
- 工艺数值建议、Isolation Forest、LSTM、真实传感器数据效果。

工作区 Fast 原缓存保留，未删除。新模块运行与测试不依赖这些缓存。代码位于本地 feature-business 工作区，未提交或推送 Git。
