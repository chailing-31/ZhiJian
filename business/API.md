# B 独立服务接口与交接规范

整理日期：2026-10-10；服务版本：0.5.0。人工说明以本文为入口，完整机器可读字段约束见 [openapi.json](openapi.json)，由当前代码生成。启动方式见 [README](README.md)，交付验收见 [DELIVERY](DELIVERY.md)。

## 服务范围与原 Word 协议

B 提供无状态 Python 计算服务，不连接数据库、不检查批次是否存在、不保存读数或人工记录、不控制设备。以下调用地址均指 **Python 8001**，并非 Java 8080。

| 能力 | B 路径 | 请求形式 | 交接边界 |
| --- | --- | --- | --- |
| 健康检查 | GET /health | 无请求体 | 进程可响应，不检查数据库或模型 |
| 加工建议 | POST /business/process-advice | Word 字符串批次号兼容；亦保留数值 ID | B 返回建议；C 校验批次及保存实际采用值 |
| 连续冷链规则 | POST /coldchain/check | 数值 batch_id + 完整 readings 数组 | C 将 Word 单点请求转换为历史数组，B 分析 |
| 规则与异常模型 | POST /coldchain/analyze | 与规则接口相同 | 可选模型能力；规则与模型分别返回 |

**Word 的冷链单点请求不能原样直接发到 Python。** Word 使用字符串 batch_id、temperature、humidity、time；B 的已实现契约使用内部数值 ID 与 readings。直接将单点 Word 请求发送到 Python 会返回 422。C 需按下表适配；接口路径相同不代表请求格式相同。

| Word / 系统侧 | C 的职责 | 传入 B |
| --- | --- | --- |
| 字符串批次号 | 查询实际批次并取得内部 ID；检查存在性 | 正整数 batch_id |
| time，例如 2026-09-23 10:20 | 外部约定北京时间时补 +08:00；校验有效日期 | readings[].timestamp |
| 本次温度、湿度 | 与该批次、同一监测点/来源历史合并；按时间校验 | readings 中的当前点 |
| 未指定数据来源 | 由系统协议明确；演示不能默认为实测 | 每条显式 simulation 或 sensor |
| 重传、乱序、并发 | 去重或拒绝冲突；确保用于计算的是一致历史 | 不含重复时刻、严格递增的数组 |
| alert/level/reason | 可透传给外部调用方，同时保存历史 episodes | B 不生成数据库告警 ID |

单个高温点没有持续时长证据，不照抄 Word 响应样例中的 alert=true。不同来源、监测点不得混传；读数对象本身不含批次字段，B 无法判断调用方是否混入另一批次，隔离由 C 保证。一次最多 10000 点；长期续算协议本版未提供，不能随意截断历史再称完整检测。

原 Word 的 POST /alert/{id}/resolve 属 C 的人工处置接口，B 没有该路由。采用记录、处置记录、公开溯源、数据库迁移均不属于 B 的交付依赖。

## 公共约定

- 请求和响应均为 JSON，POST 使用 Content-Type: application/json；本版本为内网独立计算服务，未实现鉴权。
- 未声明的字段拒绝；必填字段缺失、格式/范围不符返回 422。标识严格按类型校验，布尔值不是数值 ID。
- 冷链 ID 为 1—9223372036854775807 的整数。加工兼容此整数或 1—64 位 ASCII 字母、数字、连字符组成的字符串；保留输入类型，不做数据库映射。
- 数值须有限，拒绝 true/false、NaN、Infinity 及溢出数值。当前 Python 校验器也接受可解析的数值字符串（为 CSV 兼容保留）；JSON 调用方应传数值，不能假定 Java 适配层同样接受字符串。
- 时间使用带时区 ISO 8601，输出归一为 +08:00；不接受 Unix 数字时间戳。转换后须在 Python datetime 可表示范围内。数据库支持范围由 C 另行检查。
- demo_only=true 表示规则/模型的演示属性；source 表示输入来源。传 sensor 不会自动取消演示标记。
- /docs 为交互文档，/openapi.json 为实时契约；离线契约可通过 `python -m app.export_openapi openapi.json` 更新。

## GET /health

```json
{"status":"ok","service":"business-rules","version":"0.5.0"}
```

HTTP 200 只表明进程响应；模型未配置或加工配置不可用时也不能只凭此接口宣称所有能力就绪。

## POST /business/process-advice

| 请求字段 | 类型 / 单位 | 必填与限制 |
| --- | --- | --- |
| batch_id | 整数或字符串 | 必填，按公共约定校验 |
| grade | A / B / C / REJECT | 必填，大小写敏感；由调用方传入已确认等级 |
| temperature | 数值 / ℃ | 必填，环境温度，不是原料温度 |
| humidity | 数值 / %RH | 必填，0—100 |
| material_temperature | 数值 / ℃ 或 null | 可选，不以环境温度补齐 |

原 Word 样例可直接调用 B：

```json
{"batch_id":"APPLE-2026-001","grade":"B","temperature":5,"humidity":80}
```

响应字段：

| 字段 | 含义 |
| --- | --- |
| batch_id | 输入标识，原类型原值 |
| type / advice_type | 均为 rule；前者兼容 Word，后者兼容原内部调用方 |
| status | suggested / manual_review / blocked |
| advice.pre_cooling_time | 如 6h，或 null；null 不得显示为 0h |
| advice.washing_pressure | 如 normal 档位，或 null；不是 MPa 数值 |
| advice.action | 人工处理提示，不表示已执行加工或设备命令 |
| reason | 输入观测、命中/不适用原因、演示说明 |
| rule_id / rule_version / rule_basis | 本次规则标识、版本和依据 |
| requires_confirmation / demo_only | 本版均为 true |

当前配置 demo-process-v3：A 为 4h/normal，B 为 6h/normal；C 为 manual_review、空参数；REJECT 优先 blocked、空参数。A/B 的环境温度和可选原料温度覆盖 [0,30]℃，湿度覆盖 [40,95]%RH，端点包含。合法但超覆盖范围时为 HTTP 200 + manual_review，不是 422；湿度超出字段有效范围 0—100 则是 422。

温湿度只判断适用性，不计算最优预冷时长。REJECT/C 优先于适用范围判断；超范围原因仍保留。所有数值为演示条件，不是已核定生产参数。规则文件 [processing-rules.json](config/processing-rules.json) 修改后须更新版本、样例、测试并重启；配置不可用时 503，不返回替代参数。

完整请求/响应见 [processing-cases.json](samples/processing-cases.json)（七组）与 [PROCESSING.md](PROCESSING.md)。

## POST /coldchain/check

| 请求字段 | 类型 / 单位 | 必填与限制 |
| --- | --- | --- |
| batch_id | 正整数 | 必填，内部 ID |
| readings | 数组 | 1—10000 条，同来源，时间严格递增 |
| readings[].timestamp | 带时区 ISO 时间 | 必填；等价时区的同一时刻视为重复 |
| readings[].temperature | 数值 / ℃ | 必填，有限值；不设未经确认的生产范围 |
| readings[].humidity | 数值 / %RH | 必填，0—100 |
| readings[].source | simulation / sensor | 必填，不混用 |
| readings[].door_open | JSON 布尔或 null | 可选；不接受字符串 false |
| readings[].equipment_current | 数值 / A 或 null | 可选，非负 |
| config.temperature_upper | 数值 / ℃ | 默认 8 |
| config.duration_minutes | 数值 / 分钟 | 默认 5，范围 (0,1440] |
| config.max_gap_minutes | 数值 / 分钟 | 默认 2，范围 (0,1440] |

config 可省略或仅填写部分字段；不能传 null。最小有效请求如下，结果为 insufficient_data，不触发告警：

```json
{"batch_id":1,"readings":[{"timestamp":"2026-10-03T10:00:00+08:00","temperature":12,"humidity":85,"source":"simulation"}]}
```

完整触发样例见 [请求](samples/coldchain-request.json) 与 [响应](samples/coldchain-response.json)。

响应包含 batch_id、source、alert、level、status、reason、episodes、data_gap_count、rule_version、demo_only 和实际 config。当前规则版本 demo-coldchain-v2。

| status | 含义 | alert / level |
| --- | --- | --- |
| normal | 末点未超过本次上限，不代表食品安全或所有指标合格 | false / NONE |
| insufficient_data | 当前超温段只有一个观测点 | false / NONE |
| pending | 当前段持续时间尚未达到阈值 | false / NONE |
| active | 当前段达到持续超温条件 | true / HIGH（演示等级） |

大于 8℃才计超温；每分钟采样时第 6 个连续高温点才覆盖 5 分钟。相邻时间差恰好 2 分钟可连续，超过则断开。按采样时间而非提交速度计时，不插值。湿度、门状态、电流不参与此规则判断。

只有达到持续阈值的区段进入 episodes，每段字段如下：

| 字段 | 含义 |
| --- | --- |
| started_at | 连续超温段首个观测时间 |
| triggered_at / trigger_value | 首次观测达到时长的时刻及温度，℃ |
| last_observed_at | 本段最后一个超温观测时间 |
| recovered_at | 连续观测到温度回到上限以内的时间，未恢复则 null |
| end_reason | ongoing / recovered / data_gap |
| observed_minutes | 首末超温观测时间差，不含断点或恢复点以后的时间 |
| peak_temperature | 本段温度峰值，℃ |

恢复后 alert=false，但历史 episodes 仍保留；断点结束旧段时 end_reason=data_gap、recovered_at=null，不视为恢复或人工处置。重复提交完整数组只是重新计算相同结果，B 不产生落库副作用；C 需避免将再次返回的旧区段重复建告警。

## POST /coldchain/analyze

请求与 /coldchain/check 相同；返回 `{"rule_result": {...}, "model_result": {...}}`。请求 config 只影响规则，模型窗口和阈值由训练产物固定。

模型需安装 requirements-ml.txt、准备受信模型并配置 BUSINESS_ANOMALY_MODEL_DIR；未配置/不可加载返回 503，模型依赖不会影响规则端点启动。具体训练与验证命令见 [ANOMALY.md](ANOMALY.md)。模型文件不随轻量源码提交，可按文档复现，或由团队另行提供与清单匹配的受信产物。

model_result 字段包括：

| 字段 | 含义 |
| --- | --- |
| batch_id、model_id、model_strategy、feature_version | 标识和版本 |
| algorithm | isolation_forest / knn_distance / isolation_forest_knn |
| feature_config、feature_names | 固定窗口、缺口、最少点数和特征定义 |
| training_source、input_source、source_matches_training | 训练/输入来源是否一致；一致也不保证现场效果 |
| demo_only、explanation | 演示属性和解释边界 |
| threshold、threshold_method、confirmation_points | 固定阈值、校准方法和时序确认点数 |
| scored_count、anomaly_count | 实际可评分点数与异常点数 |
| points | 逐点结果，与输入时间顺序一致 |

每个 point 提供 timestamp、status、score、raw_score、score_components、anomaly、evidence。status 为 scored、insufficient_history 或 missing_device_fields；未评分时 score/raw_score/anomaly 为 null，不能当作正常点。score 是用于阈值比较的最终分数，不是概率；raw_score 为确认前分数，components 为各分支分数。

evidence 列出 feature、value、reference_low、reference_high、interpretation（above_reference/below_reference）、reference_type（training_percentiles/normal_neighbors）。它表示相对正常参考的偏离，不是故障原因鉴定。模型异常不能覆盖规则告警，模型无异常不能清除已有规则告警。

完整实际样例：[请求](samples/anomaly-v4-request.json)、[响应](samples/anomaly-v4-response.json)。模拟实验报告见 [OPTIMIZATION_V4.md](reports/OPTIMIZATION_V4.md)，不等于实测效果。

## 错误处理

| HTTP | 响应结构 | 调用方处理 |
| --- | --- | --- |
| 200 | 对应业务响应模型 | 仍需读取业务 status，不能一律当作建议可采用或无风险 |
| 422 | detail 数组，每项含 loc、msg、type，可能含 input/ctx | 修正字段；B 不采用 Java 的 400 错误格式 |
| 503 | detail 字符串 | 规则配置或模型未就绪，修复并重启后重试；不能伪造正常结果 |
| 404 / 405 | detail 字符串 | 路径不存在或 HTTP 方法错误 |

例如缺少 grade 时：

```json
{"detail":[{"type":"missing","loc":["body","grade"],"msg":"Field required","input":{"batch_id":1,"temperature":5,"humidity":80}}]}
```

错误文案和附加字段可能随校验库变化，调用方按 HTTP 状态和 loc/type 定位，不匹配整段自然语言。非有限非法输入在错误详情中转成字符串，以保持合法 JSON。请求超时/连接失败不代表 alert=false；B 计算无持久化副作用，可用相同输入重试。

## CSV 与独立演示

CSV 使用 UTF-8 或带 BOM 的 UTF-8，必填 timestamp、temperature、humidity、source；可选 door_open、equipment_current。可选空值转 null；CSV 的门状态接受 true/false/1/0，转换后才进入 JSON 模型；其他校验与冷链接口相同。

在 business/ 运行：

```bat
.venv\Scripts\python.exe -m app.demo processing samples/processing-v1-request.json
.venv\Scripts\python.exe -m app.demo coldchain samples/overheat.csv --batch-id 1 --interval 1
.venv\Scripts\python.exe -m app.demo coldchain samples/recovery.csv --output artifacts/recovery-demo.json
.venv\Scripts\python.exe -m app.demo coldchain samples/gap.csv
```

默认离线；加 `--base-url http://127.0.0.1:8001` 后逐次调用 B HTTP 接口，并与本地同版规则核对。失败即停止，不输出成功报告，不自动重试。冷链每一步传从头至当前点的完整历史，不是单点接口，也不是服务端持久化。

interval 为 0—10 秒，只影响播放速度；报告的 steps 保留每步状态，final_result 为最后完整结果。进度写 stderr、JSON 写 stdout；--output 额外保存 UTF-8 JSON，存在的文件不覆盖。退出码 0 成功、2 输入/调用/核验失败、130 用户中断。回放反复计算前缀，适用于小型验收样例，不是长序列性能方案；大 CSV 仅看最终结果时用 app.analyze_csv。
