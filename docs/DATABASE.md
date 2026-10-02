# 数据库约定 v1.1

数据库：

```text
smart_fresh_demo
```

## 基础表

```text
batches
inspections
processing_records
sensor_readings
alerts
batch_events
```

所有业务记录通过内部数值 `batch_id` 关联；`batch_code` 唯一，用于公开查询。

## A3 增量表

通过：

```text
database/migrations/20261001_A3_inspection_integration.sql
```

创建：

```text
inspection_payloads
inspection_reviews
```

`inspection_payloads` 保存完整 AI 原响应、幂等键、预测 UUID 和图片哈希；`inspection_reviews` 按 revision 追加人工复核历史。

不要用人工复核覆盖模型原始结果。

## 业务表语义

- `inspections`：AI 业务记录及最新人工复核快照
- `processing_records`：加工输入、建议、采用值与操作员
- `sensor_readings`：冷链/设备读数
- `alerts`：告警与处置
- `batch_events`：全链路事件

公开溯源只读取：

```text
visibility = 'public'
```

private 事件不能自动出现在消费者页面。

## 演示数据

`demo.sql` 当前只保证：

```text
APPLE-2026-001
入厂 public 事件
```

不会预造 AI、加工、冷链或出厂完成数据。

## I1 / I2

I1 `/batches/{id}/report` 只读聚合已保存的内部记录。

I2 `/trace/{batch_code}` 的 `public_summary` 只从 public `batch_events` 统计，不直接读取内部 AI / 加工 / 冷链表推导消费者结论。

## 数据库变更规则

任何新表或字段变更应：

```text
新增版本化 migration
更新 docs/DATABASE.md
更新 docs/API.md
提供升级说明
```

不要依赖 `CREATE TABLE IF NOT EXISTS` 自动升级已有表。
