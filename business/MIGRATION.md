# 从 Fast 恢复到 ZhiJian/business

日期：2026-10-03。

## 恢复来源

工作区同级 `Fast` 中的 `app/__pycache__/main.cpython-312.pyc`、`models.cpython-312.pyc`、`rules.cpython-312.pyc` 及测试字节码。原 `.py` 源码缺失；本次通过反汇编读取结构与逻辑、加载原规则验证行为后重建源码，不声称恢复了原注释或原文件的逐字内容。原 Fast 目录不修改、不作为运行依赖。

## 有意调整

| 旧 Fast | 新模块 | 原因 |
| --- | --- | --- |
| 字符串 `batch_id=APPLE-2026-001` | 正整数 `batch_id`，范围为有符号 BIGINT | 对齐 MySQL 内部 ID；公开编号仍由系统使用 `batch_code` |
| 当前点 + `history` | 一个按时间严格递增的 `readings` 数组 | 单次分析序列含历史和最新读数，适配 CSV 与后端查询 |
| `time` | `timestamp` | 对齐传感器表字段 |
| 无时区时间自动假定北京时间 | 输入必须包含时区，输出统一 +08:00 | 避免调用方时间歧义 |
| 无来源字段 | 每条读数必须有 `source=simulation/sensor` | 显式区分模拟与实测；一次调用不可混用 |
| `type=rule` | `advice_type=rule` | 对齐仓库约定 |
| 无实际触发温度 | `episodes[].trigger_value` | 与历史峰值分开，便于 C 存储触发证据 |
| 加工仅 temperature/humidity | 另支持可选 material_temperature | 区分环境温度与原料温度；不引入未经确认的数值规则 |

规则版本升级为 `demo-coldchain-v2`、`demo-process-v2`，服务版本 0.2.0。内部路径保留 `/coldchain/check`、`/business/process-advice`；它们不替代 Spring Boot 对外路径。旧请求字段会得到 422，不静默兼容错误的批次含义。

## 保留的行为

默认阈值、持续时间、最大数据间隔、异常区段和恢复语义保持原逻辑。相同输入结果确定，不在内存维护跨请求批次状态。加工仍按 A/B/C/REJECT 返回流程提示，数值工艺建议为空，保留需人工确认及仅供演示标识。

## 验证方式

恢复前在匹配 Python 3.12 中对原规则和模型完成 21 项直接检查；该结果不代表原 HTTP 服务可运行。迁移后的接口、样本、CSV 解析和校验由 `tests/test_api.py` 验证，运行方法及最新结果见 `VALIDATION.md`。未完成 Spring Boot/MySQL/Vue 全链路联调。
