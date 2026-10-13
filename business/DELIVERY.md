# B 独立交付与验收

本轮交付：加工参数演示规则、连续冷链规则、已有异常模型能力、独立演示、接口文档与测试。系统集成归 C；本轮不要求 Java、Vue 或 MySQL。

## 文件与提交范围

- 必交：app/、config/、samples/、tests/、requirements*.txt、pytest.ini、conftest.py、.gitignore，以及 README.md、API.md、openapi.json、PROCESSING.md、ANOMALY.md、VALIDATION.md 和本说明。
- 依据/研究材料：RULES_REVIEW.md、FULL_SCOPE.md、reports/、MIGRATION.md、B_ONLY_PLAN.md、NEXT_STEPS.md 按本次实际改动一并保留，历史实验不改写成当前生产效果。
- Dockerfile 是可选的规则服务运行方式，本轮验收以 Python 为准，不要求构建容器。
- 不提交 .venv、缓存、日志、artifacts/ 模型及运行报告、datasets/generated/，不加入真实账号或未授权原始数据。模型复现方式见 ANOMALY.md。
- 不需要提交根目录 scripts/、backend/、frontend/、database/ 或 Compose，才能使用这份 B 交付。

## 从干净目录验收

取得 business/ 全部交付文件后，在该目录执行。需 Python 3.12，以下命令可直接用于 CMD 或 PowerShell，无需激活环境。

```bat
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -q
```

只运行规则和独立演示可改装 requirements.txt；模型训练/推理装 requirements-ml.txt；完整测试装 requirements-dev.txt。训练产物不是规则服务依赖。

## 加工验收

```bat
.venv\Scripts\python.exe -m app.demo processing samples/processing-v1-request.json
```

应返回原字符串批次号、type=rule、status=suggested、6h/normal 和演示/人工确认标记。七组固定请求与结果见 samples/processing-cases.json；测试逐组核对。

| 情况 | 预期 |
| --- | --- |
| A/B 输入在覆盖范围 | 4h/6h 与 normal |
| C | manual_review，参数 null |
| REJECT | blocked，参数 null |
| A/B 合法但超演示范围 | manual_review，列出不适用原因 |
| 缺必填字段、非法等级/数值 | API 422，不能当作已给建议 |
| 规则配置不可用 | API 503，不返回备用参数 |

## 连续冷链验收

```bat
.venv\Scripts\python.exe -m app.demo coldchain samples/normal.csv
.venv\Scripts\python.exe -m app.demo coldchain samples/overheat.csv
.venv\Scripts\python.exe -m app.demo coldchain samples/recovery.csv
.venv\Scripts\python.exe -m app.demo coldchain samples/gap.csv
```

| 样例 | 应观察到的过程/最终状态 |
| --- | --- |
| normal | 未触发规则，episodes 为空 |
| overheat | 10:01 开始高温，10:06 触发；最终 active、1 段 ongoing |
| recovery | 第 6 点 10:05 触发；第 7 点 10:06 回落，最终 normal，保留 1 段 recovered |
| gap | 10:05 触发；10:09 高温点与上点间隔 4 分钟，旧段 data_gap，新段 insufficient_data，断点计数 1 |

重复运行相同文件不会有“旧数据库时间冲突”：这里是无状态分析，不往系统中导入。批次隔离、重复时间拒绝、阈值/间隔边界、多个区段均由自动测试覆盖。

## HTTP 接口验收

一个终端启动 B：

```bat
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

另一个终端在 business/ 执行：

```bat
curl.exe http://127.0.0.1:8001/health
.venv\Scripts\python.exe -m app.demo processing samples/processing-v1-request.json --base-url http://127.0.0.1:8001
.venv\Scripts\python.exe -m app.demo coldchain samples/recovery.csv --base-url http://127.0.0.1:8001
```

后两条会检查服务响应结构和结果是否与本地规则一致；0 退出码才算通过。也可打开 http://127.0.0.1:8001/docs 或导入 openapi.json。模型未配置时 /coldchain/analyze 返回 503 是预期行为，不代表规则服务不可用；模型验收按 ANOMALY.md 另行加载受信产物。

## 给 C 的交接清单

1. 先读 API.md 的 Word 对应表；冷链单点外部协议不等于 B 历史数组内部协议。
2. C 校验批次和来源、组织历史、去重/处理并发，再调用 B；B 不负责数据库 ID 映射和历史积累。
3. 保存规则版本、配置与 episodes；恢复、数据断点、人工处置分开。完整结果可能重复含历史区段，落库时需幂等。
4. 加工 null 参数保留为空，实际采用值由人工提交；suggested 不表示加工已执行，blocked 不表示系统已拦截。
5. 模型规则分开展示，缺历史/缺设备字段不能显示成正常，模型分数不能当概率或食品风险等级。

真实业务参数核定、真实数据效果和有数据条件后的 LSTM 属后续工作；本轮完成的是 B 演示功能与可复现交付。
