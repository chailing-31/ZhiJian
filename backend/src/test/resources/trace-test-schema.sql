DROP TABLE IF EXISTS batch_events;
DROP TABLE IF EXISTS batches;

CREATE TABLE batches(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_code VARCHAR(64) UNIQUE,
  product VARCHAR(50),
  variety VARCHAR(50),
  origin VARCHAR(100),
  supplier VARCHAR(100),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(30)
);

CREATE TABLE batch_events(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT,
  event_type VARCHAR(50),
  event_time TIMESTAMP,
  summary CLOB,
  source VARCHAR(50),
  operator VARCHAR(50),
  evidence_path VARCHAR(255),
  visibility VARCHAR(20)
);

INSERT INTO batches(
  id,batch_code,product,variety,origin,supplier,status,created_at
)
VALUES(
  1,'APPLE-2026-001','苹果','红富士','山东烟台',
  '内部供应商不应公开','created','2026-10-01 09:00:00'
);

INSERT INTO batch_events(
  batch_id,event_type,event_time,summary,source,visibility
)
VALUES
(1,'入厂','2026-10-01 09:00:00','批次登记','demo','public'),
(1,'AI质检','2026-10-01 10:00:00','内部模型记录','model','private'),
(1,'人工复核','2026-10-01 10:05:00','已保存公开复核摘要','manual','public'),
(1,'加工','2026-10-01 11:00:00','已保存公开加工摘要','rule','public'),
(1,'冷链运输','2026-10-01 12:00:00','已保存公开冷链摘要','sensor','public'),
(1,'出厂','2026-10-01 13:00:00','内部出厂草稿','manual','private');
