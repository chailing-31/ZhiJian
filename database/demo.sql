USE smart_fresh_demo;
INSERT INTO batches(batch_code, product, variety, origin, supplier, status)
VALUES ('APPLE-2026-001', '苹果', '红富士', '山东烟台', '示例合作社', 'created')
ON DUPLICATE KEY UPDATE batch_code = VALUES(batch_code);

INSERT INTO batch_events(batch_id, event_type, event_time, summary, source, visibility)
SELECT b.id, '入厂', '2026-09-24 09:00:00', '演示批次登记', 'demo', 'public'
FROM batches b WHERE b.batch_code = 'APPLE-2026-001'
  AND NOT EXISTS (SELECT 1 FROM batch_events e WHERE e.batch_id = b.id AND e.event_type = '入厂' AND e.source = 'demo');
