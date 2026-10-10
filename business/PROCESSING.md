# 加工参数建议

服务版本 0.5.0，规则版本 demo-process-v3。按《智检鲜达_Demo_API接口规范_v1.0》第五节返回规则建议，不作自动优化或 LSTM 预测。

## 演示规则

| 人工确认等级 | 状态 | 预冷时长 | 清洗压力档位 | 处理方式 |
| --- | --- | --- | --- | --- |
| A | suggested | 4h | normal | 核对工序、设备并确认后采用 |
| B | suggested | 6h | normal | 复核外观和分选结果后确认采用 |
| C | manual_review | null | null | 负责人先复核用途和加工适用性 |
| REJECT | blocked | null | null | 暂停流转提示，交负责人复核处置 |

**这是工程演示方案，不是已验证的生产参数。** B 级 6h/normal 沿用接口文档样例；A 级 4h 和适用范围为人为演示设定，不代表已建立物理模型或质量优化关系。normal 是档位标签，不是 MPa 数值；需按实际设备和作业规程映射，接口不发送设备控制命令。

A/B 建议适用范围为环境温度 [0,30]℃、相对湿度 [40,95]%RH；可选原料温度提供时须在 [0,30]℃。这些区间只是演示规则覆盖范围，不是贮藏标准或安全界限。温湿度只判断适用性，不用于计算最优时长。

超出任一区间返回 manual_review、空参数和全部不适用原因。REJECT 的暂停提示优先，C 级仍先复核；即使环境适用，也不给这两个等级的参数。原料温度缺省时不阻塞原文档样例，原因中明确记录未提供，绝不使用环境温度替代。

缺必填字段、非法等级、非有限数值或数值字段传布尔值返回422；合法但规则未覆盖的数值返回200与manual_review。

## 配置维护

[config/processing-rules.json](config/processing-rules.json) 保存版本、依据、输入区间及四个等级的规则。加载时校验范围顺序、湿度有效范围、四级完整性、规则标识唯一性、拒收暂停和状态/参数一致性；错误配置不会静默返回旧参数。

配置在进程内缓存；修改时同步更新 version、basis、样例和预期测试，再重启服务。当前没有在线规则管理后台。后续有业务依据时再修改映射；demo_only 始终为 true，不会因编辑配置就宣称生产验证通过。

## 调用和输出

接口为 POST /business/process-advice。兼容 Word 字符串批次号和原有正整数 ID，原类型原值返回，不查询或转换数据库标识。字符串限 1—64 位 ASCII 字母、数字、连字符；字符串 "1" 不是整数 1，Java 应按类型解析并检查批次存在。

返回 type=rule，同时保留 advice_type=rule；新增 status、rule_id、rule_basis，保留 rule_version、demo_only=true、requires_confirmation=true。详细约定见 [内部接口](../docs/BUSINESS_API.md)。冷链的数值 ID 和历史数组协议未变。

在 business 目录启动服务，另开终端从同一目录调用：

```bat
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
curl.exe -X POST http://127.0.0.1:8001/business/process-advice -H "Content-Type: application/json" --data-binary "@samples/processing-v1-request.json"
```

上述 JSON 文件就是 Word 的请求：

```json
{"batch_id":"APPLE-2026-001","grade":"B","temperature":5,"humidity":80}
```

预期为 suggested、6h、normal，并有演示依据和人工确认要求。[processing-cases.json](samples/processing-cases.json) 保存四个等级和三种超范围情况共七组请求及完整输出。原有数值 ID 的 processing-request.json / processing-response.json 继续保留。

## 验证与边界

执行 `.venv\Scripts\python.exe -m pytest -q`。测试覆盖 Word 样例、标识类型、区间端点、拒收优先级、缺字段、非法输入、配置错误、跨请求隔离及保存样例，并保留冷链和模型回归测试。

本模块只返回建议。C 仍须完成批次校验、输入/建议/实际采用值保存及页面接入；suggested 不表示已经加工，blocked 也不表示已实现系统流转拦截。正式应用前需确认工序、设备、参数依据及实际效果。
