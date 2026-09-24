# 数据库约定 v1.0

MySQL 8 数据库 `smart_fresh_demo`。`batches.id` 是内部主键，其他五表均用 `batch_id` 关联；`batch_code` 唯一，用于二维码公开查询。六表分别为 `batches`、`inspections`、`processing_records`、`sensor_readings`、`alerts`、`batch_events`。具体字段见 `../database/tables.sql`。

视觉原始结果保留在 `detections_json`，人工最终结果保留在 `final_grade`；业务建议标记 `advice_type=rule`，传感器模拟值标记 `source=simulation`；公开溯源事件须 `visibility=public`。`demo.sql` 仅生成批次和入厂事件，避免把尚未执行的质检、加工、冷链流程预写成已完成。正式实现前确认审阅原因字段是否扩充到表结构。
