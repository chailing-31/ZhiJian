-- Additive migration for the existing smart_fresh_demo schema.
-- Run explicitly AFTER tables.sql, with mysql --default-character-set=utf8mb4.
-- Does not delete, change, or recreate existing business records.
USE smart_fresh_demo;

CREATE TABLE IF NOT EXISTS inspection_payloads (
  inspection_id BIGINT PRIMARY KEY,
  request_id CHAR(36) NOT NULL UNIQUE,
  image_sha256 CHAR(64) NOT NULL,
  prediction_id CHAR(36) NOT NULL UNIQUE,
  response_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_a3_payload_inspection FOREIGN KEY (inspection_id) REFERENCES inspections(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspection_reviews (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  inspection_id BIGINT NOT NULL,
  revision INT NOT NULL,
  conclusion VARCHAR(40) NOT NULL,
  reviewer VARCHAR(50) NOT NULL,
  remark TEXT NOT NULL,
  final_grade VARCHAR(20) NULL,
  candidate_reviews JSON NOT NULL,
  publish_summary BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_a3_review_revision UNIQUE (inspection_id, revision),
  CONSTRAINT fk_a3_review_inspection FOREIGN KEY (inspection_id) REFERENCES inspections(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Rerunning this script does not upgrade an incompatible pre-existing table.
-- API /inspection-service/ready checks the expected columns before upload.
