# B 模块验证记录

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
