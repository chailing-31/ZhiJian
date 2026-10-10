# B 模块内部接口 v0.5.0

实现位于 `business/`。这是 Spring Boot 调用的内部规则服务，不是浏览器直连接口，不访问数据库。运行方法和样本见 [business/README.md](../business/README.md)。

## 调用关系与状态

2026-10-10：Java与页面已接入连续超温规则、历史保存、去重和人工处置，见 [COLDCHAIN.md](COLDCHAIN.md)。本文Python路径位于8001；同名原Word单点兼容路径位于Java 8080，不混用请求结构。

```text
Vue → Spring Boot（批次验证、历史查询、落库与处置）→ Python business（规则计算）
```

Python 内部接口已实现；Spring Boot 对外接口状态如下：

| 对外路径 | 内部调用 / 行为 |
| --- | --- |
| `POST /batches/{id}/processing-advice` | 查询并确认原料等级；构造含数值 batch_id 的请求，调用 `/business/process-advice`，保存 inputs_json、advice_json、advice_type |
| `POST /batches/{id}/sensor-readings` | 已实现：单点校验、历史查询、规则调用及事务保存 |
| `POST /alert/{id}/resolve`（兼容 `/alerts/{id}/resolve`） | 已实现：Java保存人工处置信息，无对应Python接口 |

加工Java代理及采用值保存仍待接入。冷链历史和告警由 `GET /batches/{id}/coldchain` 查询，页面将CSV解析后逐点上报，不提供文件上传接口。Python不验证batch_id是否存在，Java调用前查库；分析不会产生加工完成或人工处置事件。

## 公共约定

- 冷链 `batch_id`：严格正整数，不接受字符串/布尔值/小数，最大 9223372036854775807；取自真实批次 API，不硬编码 1。加工接口另兼容 Word v1.0 的字符串批次号，详见加工节；Python 只原样返回标识，不查询或转换数据库 ID。
- 请求和响应为 JSON。字段错误返回 FastAPI 422 `detail` 列表；这不是 Spring Boot 对外错误协议，C 需按外部规范转换。额外字段拒绝。
- 时间：ISO 8601，必须含时区；统一返回 `+08:00`。表示同一时刻的不同偏移时间仍被认定为重复。
- 数值字段不接受布尔值 true/false；所有浮点值必须有限，不能传 NaN/Infinity 或溢出的数值。无效数值返回422；错误详情中的非有限输入以字符串表示，以保证响应为合法JSON。
- 时间转换到 `+08:00` 后必须处于支持的日期范围，越界返回422。
- `demo_only=true` 描述规则未经生产验证；数据来源由 `source` 独立描述。

## GET /health

响应：`{"status":"ok","service":"business-rules","version":"0.5.0"}`。只表示服务进程可响应，不表示数据库或算法模型已经就绪。

## POST /coldchain/check

完整请求与响应见 `business/samples/coldchain-request.json`、`coldchain-response.json`。

| 字段 | 类型 / 单位 | 要求 |
| --- | --- | --- |
| batch_id | 整数 | 必填，内部 ID |
| readings | 数组 | 1—10000 条；同批次、同来源，时间严格递增，包含历史和当前点 |
| readings[].timestamp | 带时区时间 | 必填 |
| readings[].temperature | 数值 / ℃ | 必填，本版仅校验有限数值；不设未经确认的工艺范围 |
| readings[].humidity | 数值 / %RH | 必填，0—100 |
| readings[].source | simulation / sensor | 必填，不自动假定为实测 |
| readings[].door_open | 布尔 / null | 可选，JSON 必须使用 true/false |
| readings[].equipment_current | 数值 / A / null | 可选，非负 |
| config.temperature_upper | 数值 / ℃ | 默认 8，仅演示；大于阈值才计超温 |
| config.duration_minutes | 数值 / 分钟 | 默认 5，范围 (0,1440] |
| config.max_gap_minutes | 数值 / 分钟 | 默认 2，范围 (0,1440]；大于此间隔打断连续性 |

响应顶层：`batch_id`、`source`、`alert`、`level`、`status`、`reason`、`episodes`、`data_gap_count`、`rule_version`、`demo_only`、实际使用的 `config`。

`status` 为 `normal`（末点未超温）、`insufficient_data`（当前超温段仅有一个点）、`pending`（持续时间不足）、`active`（持续超温）。`alert` 仅反映序列末尾的活动异常；恢复后为 false，但 `episodes` 仍可非空。`level=HIGH/NONE` 也是末尾状态，HIGH 是演示级别，不是已验证的食品风险评级。

每段 `episodes` 提供：

| 字段 | 含义 |
| --- | --- |
| started_at | 该连续超温段首个观测时刻 |
| triggered_at | 首次观测到达到持续时长的时刻；不插值 |
| trigger_value | triggered_at 对应温度，℃ |
| last_observed_at | 该段最后一个超温观测时刻 |
| recovered_at | 连续观测到恢复正常的时刻，未知则 null |
| end_reason | ongoing / recovered / data_gap |
| observed_minutes | 首末超温观测时间差，不含数据空缺，也不延伸到恢复点 |
| peak_temperature | 该超温段峰值，℃ |

仅达到持续阈值的超温段进入 episodes。湿度、门状态、电流不参与本版异常判定，不据此自动推断故障原因。

### Java 落库注意事项

迁移 `database/migrations/002_coldchain.sql` 已补齐触发/恢复时间、规则版本、来源、配置、峰值和结束原因；`sensor_readings` 增加流内时间唯一约束。Java保存历史episodes，顶层 `level=NONE` 不代表历史区段无需保存。实际启动前须对已有数据库执行迁移。

Python每次重算完整序列，会再次返回旧episodes，不生成数据库告警ID。Java已按batch_id、source、规则版本、配置和started_at生成稳定事件键，并在事务中更新同段。已有流配置改变或新增点超过10000条返回409，不截断历史；长期状态承接仍未实现。

`end_reason=recovered` 表示观测恢复，不等于人工处置；`data_gap` 表示连续性中断，不能据此把告警关闭。Java 人工处置仍使用 resolved_by、resolved_at、resolution 等现有字段。所有读数必须在 Java 侧按批次过滤，本服务无法从不带 batch_id 的单条读数识别调用方混入的其他批次。

## POST /business/process-advice

完整样例见 `business/samples/processing-request.json`、`processing-response.json`。

| 字段 | 要求 |
| --- | --- |
| batch_id | 必填：原有数值内部 ID，或 Word v1.0 字符串批次号（1—64 位 ASCII 字母、数字、连字符），保留原类型返回 |
| grade | 人工确认等级 A / B / C / REJECT，必填 |
| temperature | 环境温度，℃，有限数值，必填 |
| humidity | 环境相对湿度，%RH，0—100，必填 |
| material_temperature | 原料温度，℃，有限数值，可选 |

输出为 `batch_id`、`type=rule`（Word 字段）、`advice_type=rule`（兼容已有调用方）、`advice`、`reason`、`rule_version`、`requires_confirmation=true`、`demo_only=true`，以及新增的 `status`、`rule_id`、`rule_basis`。字符串形式的 `"1"` 是批次号，不会被转换成数据库 ID 1；标识映射和批次存在性验证由 Java 负责。

`status` 为 `suggested`（匹配演示参数）、`manual_review`（等级或输入条件需要复核）、`blocked`（REJECT 暂停提示）。`rule_id` 标识本次判定规则，`rule_basis` 说明工程演示依据。`advice.action` 是处理提示，不是已执行的设备命令。

规则配置为 `business/config/processing-rules.json`，版本 `demo-process-v3`。A 为 4h/normal，B 为 6h/normal；C 和 REJECT 的参数为空。配置适用区间为环境温度 [0,30]℃、湿度 [40,95]%RH；可选原料温度提供时也须在 [0,30]℃。这些范围与 A 级 4h 均为人为设定的演示条件；B 级 6h/normal 沿用 Word 样例，不是生产参数或安全判据。温湿度仅参与规则适用性判断，不用于推算最优时长；缺省原料温度不从环境温度替代，响应原因明确说明未提供。

REJECT 优先返回暂停提示；C 优先人工复核；A/B 超出任一配置区间时返回 `manual_review`、空参数以及所有不适用原因。合法但未覆盖的条件不是字段错误；缺少必填字段、非法等级、非有限值等仍返回422。`normal` 只是清洗压力档位标签，不是 MPa 数值，设备档位映射和实际工序须人工确认。所有结果保留演示及需确认标识。

配置加载时验证范围顺序、四个等级及建议状态与参数的一致性；进程内缓存，修改后更新规则版本并重启服务。参数为空时不能显示成 0。Java 可以将建议存为草稿，但只有人工提交实际采用值后才能生成实际加工事件；本轮不实现持久化或页面接入。详细规则和运行样例见 `business/PROCESSING.md`。

## CSV 工具

`python -m app.analyze_csv 文件.csv --batch-id 实际ID [--config 配置.json]` 输出与冷链接口一致的JSON，仅离线计算、不写库。UTF-8/UTF-8 BOM，必需列timestamp,temperature,humidity,source，可选door_open,equipment_current；可选空值为null，door_open接受true/false/1/0。格式错误返回非零退出码。浏览器已支持CSV解析后逐点上报与定速播放；命令行连续演示见 `scripts/replay_coldchain.py`。

## POST /coldchain/analyze（0.3.0新增）

输入与 `/coldchain/check` 相同。输出 `rule_result`（原规则响应）及 `model_result`（已训练的异常模型结果），不修改已有规则端点。模型需由运行方提前训练，并以环境变量 `BUSINESS_ANOMALY_MODEL_DIR` 配置；不存在或版本不符返回503，不返回假成功。模型目录不是请求字段。

模型响应包含 model_id、model_strategy、feature_version、feature_config、训练/输入来源、source_matches_training、demo_only、threshold、threshold_method、feature_names、scored_count、anomaly_count、points。points逐点提供 timestamp、status、score、anomaly、evidence；未积累完整窗口或设备字段缺失时不评分，score/anomaly为null。证据给出偏离训练参考范围的特征值与上下界，不能当作故障原因。

0.4.0扩展：`algorithm` 可为 `isolation_forest`、`knn_distance` 或 `isolation_forest_knn`，不要在Java端只接受单一枚举。`confirmation_points` 表示模型是否采用时序确认。每点增加 `raw_score`、`score_components`（窗口距离、当前水平距离、森林各自的归一化分数）；`score` 才是与 `threshold` 比较的最终分数。未评分时raw_score为null、components为空。evidence增加reference_type，区分全体正常训练分位数和相似正常邻域参考。分数都不是概率。

当前推荐的模拟实验模型是 `artifacts/optimization-v4/optimized`，策略hybrid_distance；详细证据见 [优化报告](../business/reports/OPTIMIZATION_V4.md)。历史v1特征模型仍可加载，已有规则请求和响应保持原语义。每次请求需携带连续历史窗口；服务不跨请求自动累计模型状态。

请求 config 只影响 rule_result；模型特征及阈值由训练产物固定。模型分数不是概率，不能与 HIGH 风险等级直接换算。规则告警和模型异常需分别展示/存储，模型无异常不能清除规则告警。详细配置、数据格式和可复现实验见 [ANOMALY.md](../business/ANOMALY.md)。Java代理、记录表扩展和页面接入仍待 C 完成。
