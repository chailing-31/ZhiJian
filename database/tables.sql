CREATE DATABASE IF NOT EXISTS smart_fresh_demo DEFAULT CHARACTER SET utf8mb4;
USE smart_fresh_demo;

CREATE TABLE IF NOT EXISTS batches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_code VARCHAR(64) NOT NULL UNIQUE,
  product VARCHAR(50), variety VARCHAR(50), origin VARCHAR(100), supplier VARCHAR(100),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP, status VARCHAR(30)
);
CREATE TABLE IF NOT EXISTS inspections (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL,
  image_path VARCHAR(255), result_image_path VARCHAR(255), model_version VARCHAR(50),
  detections_json JSON, suggested_grade VARCHAR(20), final_grade VARCHAR(20),
  reviewer VARCHAR(50), reviewed_at DATETIME, created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (batch_id) REFERENCES batches(id)
);
CREATE TABLE IF NOT EXISTS processing_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, inputs_json JSON,
  advice_json JSON, advice_type VARCHAR(30), adopted_values_json JSON,
  operator VARCHAR(50), created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (batch_id) REFERENCES batches(id)
);
CREATE TABLE IF NOT EXISTS sensor_readings (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, timestamp DATETIME,
  temperature FLOAT, humidity FLOAT, door_open BOOLEAN, equipment_current FLOAT,
  source VARCHAR(30), FOREIGN KEY (batch_id) REFERENCES batches(id)
);
CREATE TABLE IF NOT EXISTS alerts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, started_at DATETIME,
  level VARCHAR(20), reason VARCHAR(255), trigger_value VARCHAR(100), status VARCHAR(20),
  resolved_by VARCHAR(50), resolved_at DATETIME, resolution TEXT,
  FOREIGN KEY (batch_id) REFERENCES batches(id)
);
CREATE TABLE IF NOT EXISTS batch_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, event_type VARCHAR(50),
  event_time DATETIME, summary TEXT, source VARCHAR(50), operator VARCHAR(50),
  evidence_path VARCHAR(255), visibility VARCHAR(20),
  FOREIGN KEY (batch_id) REFERENCES batches(id)
);
