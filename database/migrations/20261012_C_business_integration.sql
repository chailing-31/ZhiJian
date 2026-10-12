-- C integration tables for B processing and cold-chain results.
-- Additive and idempotent: it does not rewrite existing business rows.
USE smart_fresh_demo;

CREATE TABLE IF NOT EXISTS coldchain_episode_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  source VARCHAR(20) NOT NULL,
  episode_key CHAR(64) NOT NULL UNIQUE,
  alert_id BIGINT NULL,
  started_at DATETIME NOT NULL,
  triggered_at DATETIME NULL,
  last_observed_at DATETIME NULL,
  recovered_at DATETIME NULL,
  end_reason VARCHAR(20) NOT NULL,
  trigger_value DOUBLE NULL,
  observed_minutes DOUBLE NULL,
  peak_temperature DOUBLE NULL,
  level VARCHAR(20) NOT NULL,
  current_status VARCHAR(20) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  rule_version VARCHAR(100) NOT NULL,
  config_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_coldchain_episode_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  CONSTRAINT fk_coldchain_episode_alert FOREIGN KEY (alert_id) REFERENCES alerts(id),
  INDEX idx_coldchain_episode_batch (batch_id, started_at),
  INDEX idx_coldchain_episode_alert (alert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS c_integration_metadata (
  name VARCHAR(80) PRIMARY KEY,
  value VARCHAR(255) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO c_integration_metadata(name, value)
VALUES ('business_api_version', '0.5.0'), ('coldchain_persistence_version', 'c-20261012-v1')
ON DUPLICATE KEY UPDATE value = VALUES(value);
