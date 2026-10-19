# 冷链异常算法：训练、评估与接入

本模块在阈值规则基线之外提供 Isolation Forest 与正常邻域距离混合检测。完整范围见 [FULL_SCOPE.md](FULL_SCOPE.md)；`/coldchain/check` 保持独立，`/coldchain/analyze` 返回规则与模型两份结果。0.4.0 的同条件对比见 [最新优化报告](reports/OPTIMIZATION_V4.md)。当前模型只经过模拟数据实验，尚无真实业务效果验证。

## 1. 算法流程

```text
正常训练序列 → 因果时间窗口特征 → 森林/正常邻域距离拟合
独立正常校准序列 → 异常分数分布 → 固定报警分数阈值
独立带标签验证序列 → 误报及事件/场景覆盖约束选型 → 保存固定模型
多组全新带标签测试序列 → 配对比较点指标、事件、延迟、错误案例
```

每个窗口只使用当前和过去的读数；完整跨度默认5分钟，至少3点。间隔超过2分钟重置窗口；设备字段缺失时不填0，明确返回 `missing_device_fields`，并重新累积连续窗口。

| 特征配置 | 使用内容 |
| --- | --- |
| environment | 当前温湿度、温湿度变化速率、温度标准差与极差、湿度标准差、窗口温度趋势，共8项 |
| device | 上述8项加开门比例、电流均值/标准差/变化速率，共12项 |

上述为 `coldchain-causal-v1` 特征。新距离/混合模型使用 `coldchain-causal-v2`：device再增加当前门状态和当前电流，共14项，使瞬时状态与历史窗口分开；environment仍为8项。历史v1模型继续按原特征推理，不静默改变旧模型的输入。

联合模型 `joint` 使用全部选定特征。分组模型 `grouped` 同时训练联合、温度、湿度、设备子模型；environment 配置不含设备组。各组分数按正常训练分布的中位数和95%分位间距归一化，再取最大值；最终阈值统一在独立正常校准集确定。分组是为了检验单个变量异常被其他正常变量稀释的问题，不能事先断言效果更好。

模型参数：每个森林200棵树、`max_samples=auto`、`random_state=42`、`n_jobs=1`。原始异常分数取 `-score_samples`，越大越异常；联合或分组分数都不是概率、风险等级或食品合格结论。方法定义参考 [scikit-learn IsolationForest 文档](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html)。

`grouped_levels` 进一步增加温度、湿度和电流均值的单变量森林，作为第三个候选，检验持续水平偏移的漏检问题。

0.4.0增加三个可比较候选：

- `distance`：训练集各列以中位数/IQR缩放，IQR接近0的列以max(训练范围,1)缩放；取20个正常近邻的平均欧氏距离。距离不会在超过树分裂范围后维持同一个路径分数。训练参考距离排除样本自身，最终门槛仍由独立正常校准数据决定。
- `hybrid_distance`：同时计算全窗口近邻距离、当前温湿度/门/电流水平的近邻距离，以及联合Isolation Forest分数。各分支除以自身正常训练分数98%分位值，再取最大值；整体在独立正常校准集统一定阈值。水平分支降低变化速率、波动等特征对微小持续偏移的稀释。environment的水平分支只含当前温湿度。
- `hybrid_persistent`：对上述混合分数作因果三点中位数，即最近三次可评分读数至少两次超过门槛才报警；不足三次用0填充确认历史，数据缺口/字段缺失重置。校准也逐序列经过同一确认步骤。该候选在本轮验证中未胜出，不能预设平滑一定更好。

近邻实现参考 [scikit-learn NearestNeighbors](https://scikit-learn.org/stable/modules/generated/sklearn.neighbors.NearestNeighbors.html)，中位数/IQR缩放原理见 [RobustScaler](https://scikit-learn.org/stable/modules/generated/sklearn.preprocessing.RobustScaler.html)；这里显式处理稀疏门状态列的零IQR，没有按异常标签拟合距离尺度。

`--calibration-fpr` 默认2%，也可给 `--calibration-grid 0.005 0.01 0.02`，此时仅允许 `auto_validation`。每个候选用正常校准分数的相应分位值定阈值，严格大于才提示异常；校准目标不保证未来误报率。当前选型先要求验证集总体误报率≤5%、事件召回≥90%、最弱异常场景点召回≥80%，满足者按F1选；无满足者按误报约束下的事件/最弱场景召回选择，并明确标记未达目标。该目标是本轮工程选型约束，不是已确认的业务验收标准。测试指标不参与选型。

森林证据使用正常训练特征1%—99%参考范围；新模型优先使用相似正常邻域5%—95%参考范围，最多3项特征，包含数值、参考上下界及reference_type。水平距离占主导时解释该分支的水平邻域；其他情况提供全窗口近邻参考。这些证据是辅助解释，不是严格因果归因或设备故障诊断。输出保留每个分支分数，便于核对报警依据。

## 2. 复现实验

在 `business` 目录执行，Python 3.12。完整开发依赖包括算法和测试。

```bat
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -q
.venv\Scripts\python.exe -m app.anomaly_dataset --output datasets/generated/experiment-new --seed 910002 --with-validation
.venv\Scripts\python.exe -m app.anomaly_experiment --manifest datasets/generated/experiment-new/manifest.json --output artifacts/experiment-new --profile device --strategy auto_validation --calibration-grid 0.005 0.01 0.02
```

若指定目录已有清单或结果，命令会拒绝覆盖；另取目录名复现。历史三轮见 [原始结果](reports/ANOMALY_RESULTS.md)：第一轮seed=20261003，第二轮34001，第三轮910002。当时只有森林候选且选型规则不同；0.4.0的auto_validation已扩展，不能宣称上述新命令会复现旧的选型过程。旧模型、数据与原始指标均保留。

本轮优化在第三轮train/calibration/validation上开发，未将第三轮test再次当作最终证明。固定六种策略×三个校准目标后，保存选型与模型，再评价三个新种子的数据；新旧模型使用相同训练/校准输入。可复现本轮比较：

```bat
.venv\Scripts\python.exe -m app.anomaly_dataset --output datasets/generated/optimization-holdout-4800100 --seed 4800100
.venv\Scripts\python.exe -m app.anomaly_dataset --output datasets/generated/optimization-holdout-6800200 --seed 6800200
.venv\Scripts\python.exe -m app.anomaly_dataset --output datasets/generated/optimization-holdout-8800300 --seed 8800300
.venv\Scripts\python.exe -m app.anomaly_benchmark --development-manifest datasets/generated/iforest-v3/manifest.json --test-manifests datasets/generated/optimization-holdout-4800100/manifest.json datasets/generated/optimization-holdout-6800200/manifest.json datasets/generated/optimization-holdout-8800300/manifest.json --output artifacts/optimization-v4
```

已有生成数据可直接复用，输出另选新目录。全新环境需先用seed=910002、`--with-validation`生成iforest-v3目录。比较程序仅取各holdout清单的test序列，未用其中额外生成的train/calibration。它保存冻结时的代码/模型哈希、数据指纹、逐序列指标和配对bootstrap区间；相同数据不能通过更换batch_id绕过重复检查。bootstrap按场景对整条序列重采样，不能把重叠窗口当成独立观测。

生成器按独立序列划分：训练12条、正常校准4条；启用 `--with-validation` 后验证24条，最终测试24条。每条约180个分钟采样点。模拟场景包括正常波动、正常短时开门、持续高温、缓慢升温、温度振荡、湿度下降、电流升高和数据中断。4℃与85%是工程数据中心值，不是已核定的红富士贮藏参数。

`label=1` 表示生成器注入的异常时段，不表示经专家核实的故障。正常短时开门和数据中断场景不标为产品异常；中断由数据质量状态单独体现。改变生成器规则或用测试结果反复调参后，必须重新划定最终测试集。避免将测试数据用于拟合或参数选择的原则参考 [scikit-learn 数据泄漏说明](https://scikit-learn.org/stable/common_pitfalls.html#data-leakage)。

## 3. 实验输出

| 文件 | 内容 |
| --- | --- |
| model.joblib | 本机训练的模型；不从外部请求加载任意文件 |
| manifest.json | 模型ID、特征版本/配置、策略、训练/校准窗口及批次、数据指纹、阈值、依赖版本、文件校验和 |
| selection.json | 候选模型验证结果、选择方法和目标是否满足；在最终测试前保存 |
| evaluation.json | 最终测试指标、各场景结果、覆盖率、模型与规则配置、数据指纹及局限 |
| predictions.csv | 每点标签、规则判断、模型状态/分数/判断 |
| error-cases.json | 误报与漏报点、规则/模型差异和参考偏离证据 |

生成的数据与模型文件体积较大，已通过 `business/.gitignore` 排除。可复现代码和依赖文件应提交；结果摘要及 JSON 证据存入 `reports/` 以便评审。详见 [实验结果记录](reports/ANOMALY_RESULTS.md)。

比较口径：点指标只在模型有完整窗口的同一组采样点上计算，分别给出 TP/FP/TN/FN、Precision、Recall、F1、误报率；未评分点及其中的异常点另外计数。事件指标按每段注入异常是否被检测计算，并报告漏检数、检出事件的中位/最大延迟。检出过一次不代表持续异常全段都检出。无预测阳性或无实际阳性时相应指标为 null，而不是伪造为100%。

阈值基线只检测持续高温，算法涉及更多变量。因此同时展示各场景结果，不能仅凭混合场景总分宣称“全面优于规则”。滑动窗口相互相关，点数不能视为同等数量的独立实验。

## 4. 接入真实数据的格式

CSV 必需列为 `timestamp,temperature,humidity,source,label`；device 配置另需 `door_open,equipment_current`。时间含时区，单位与原传感器接口一致。来源实测填 sensor，模拟填 simulation；不混用。训练和校准 CSV 必须经确认均为正常、label均为0；验证和测试需有人工核对的标签。未知工况不能自动标成0。

清单格式示意（实际须包含所有使用的划分；以下省略后续条目）：

```json
{
  "format_version": 1,
  "source": "sensor",
  "sequences": [
    {"id": "train-001", "batch_id": 101, "split": "train", "scenario": "verified_normal", "file": "train-001.csv"}
  ]
}
```

`split` 使用 train/calibration/validation/test。相同批次不得跨集合；序列指纹完全重复时拒绝。传入的序列应来自同一监测点、相同测量条件；当前字段没有设备ID，采集方需先按监测点分组，再划分时间和批次，不能把多个传感器的读数拼成一条时序。标签不会进入特征；为设备缺字段的数据训练时，应显式选择 environment 配置并重新训练。

程序检查只能发现部分重复/划分错误，不能验证人工标签或排除所有近似数据泄漏；实测实验仍需保存来源及划分依据。最低训练/校准窗口数量是软件检查门槛，不代表真实业务样本已经足够。

## 5. 内部 API

只安装运行算法的依赖可用 `requirements-ml.txt`。PowerShell 中指定由本机训练产生的模型目录，再启动：

```powershell
$env:BUSINESS_ANOMALY_MODEL_DIR = (Resolve-Path artifacts/optimization-v4/optimized).Path
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

`POST /coldchain/analyze` 输入与 `/coldchain/check` 相同，返回：

```text
rule_result  原有阈值规则结果
model_result 模型ID、策略、特征配置、训练/输入来源、阈值、每点分数和证据
```

优化模型的实际输入输出保存在 `samples/anomaly-v4-request.json` 和 `samples/anomaly-v4-response.json`；原anomaly-request/response保留对应第三轮历史模型。输入batch_id=17仅作独立调用示例，联调需换为实际内部ID；样本来自模拟测试序列，不含真实设备数据。服务启动后可执行：

```bat
curl.exe -X POST http://127.0.0.1:8001/coldchain/analyze -H "Content-Type: application/json" --data-binary "@samples/anomaly-v4-request.json"
```

请求 `config` 只控制规则基线；模型窗口/阈值由已训练模型确定，不能通过当前请求重新拟合。模型侧每点 `status=scored/insufficient_history/missing_device_fields`；未评分时 score/anomaly 为 null。source_matches_training 只比较 simulation/sensor 标识，不代表设备、品种或业务分布一致；当前所有模型结果仍为 demo_only。

0.4.0的algorithm可为isolation_forest、knn_distance、isolation_forest_knn。每点score_components给出分支分数，raw_score为确认前分数，score为最终与threshold比较的分数；未评分时两种score均为null，分量为空。确认型候选可能因过去两点而在当前原始分数下降后仍报警，不能把当前证据解释成历史报警的严格归因。服务无状态，每次请求须携带足够连续历史；更短请求的暖机状态可能不同。

未安装算法依赖、未配置模型或版本/文件校验失败时返回503，不假装已分析。更换模型需重启服务，进程内缓存不会自动热更新。joblib 只能读取受信的本机产物，校验和检查不是对不受信文件的安全认证；HTTP 不接受模型路径或上传模型文件。

Java 应分别保存规则依据与模型版本、分数、阈值、特征证据。模型异常是人工排查线索，不能覆盖规则已触发的告警或自动写“已处置”。C 的持久化/页面接入仍未完成。
