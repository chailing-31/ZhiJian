-- Additive Q4 migration. Select smart_fresh_c_review explicitly with the launcher.
-- No USE, DROP, ALTER, DELETE, TRUNCATE, or changes to old business records.
CREATE TABLE IF NOT EXISTS q4_rounds (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL,
 sampling_note TEXT NOT NULL,
 created_by VARCHAR(50) NOT NULL,
 request_id CHAR(36) NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_q4_round_batch(batch_id,id),
 CONSTRAINT fk_q4_round_batch FOREIGN KEY(batch_id) REFERENCES batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_round_images (
 round_id BIGINT NOT NULL,
 inspection_id BIGINT NOT NULL,
 sample_code VARCHAR(64) NULL,
 PRIMARY KEY(round_id,inspection_id),
 CONSTRAINT fk_q4_image_round FOREIGN KEY(round_id) REFERENCES q4_rounds(id),
 CONSTRAINT fk_q4_image_inspection FOREIGN KEY(inspection_id) REFERENCES inspections(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_assessments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL,
 revision INT NOT NULL,
 final_grade VARCHAR(16) NOT NULL,
 intended_use VARCHAR(40) NOT NULL,
 grade_basis TEXT NOT NULL,
 grade_policy_version VARCHAR(60) NOT NULL,
 approved_by VARCHAR(50) NOT NULL,
 evidence_fingerprint CHAR(64) NOT NULL,
 evidence_json JSON NOT NULL,
 request_id CHAR(36) NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_q4_assessment_revision(batch_id,revision),
 CONSTRAINT fk_q4_assess_batch FOREIGN KEY(batch_id) REFERENCES batches(id),
 CONSTRAINT fk_q4_assess_round FOREIGN KEY(round_id) REFERENCES q4_rounds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_processing_links (
 processing_id BIGINT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 assessment_id BIGINT NOT NULL,
 request_id CHAR(36) NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_q4_process_record FOREIGN KEY(processing_id) REFERENCES processing_records(id),
 CONSTRAINT fk_q4_process_batch FOREIGN KEY(batch_id) REFERENCES batches(id),
 CONSTRAINT fk_q4_process_assess FOREIGN KEY(assessment_id) REFERENCES q4_assessments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_operations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 assessment_id BIGINT NOT NULL,
 processing_id BIGINT NULL,
 kind VARCHAR(32) NOT NULL,
 performed_at DATETIME NOT NULL,
 operator VARCHAR(50) NOT NULL,
 note TEXT NOT NULL,
 processing_fingerprint CHAR(64) NULL,
 request_id CHAR(36) NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_q4_operations(batch_id,id),
 CONSTRAINT fk_q4_op_batch FOREIGN KEY(batch_id) REFERENCES batches(id),
 CONSTRAINT fk_q4_op_assess FOREIGN KEY(assessment_id) REFERENCES q4_assessments(id),
 CONSTRAINT fk_q4_op_process FOREIGN KEY(processing_id) REFERENCES processing_records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_coldchain_results (
 batch_id BIGINT NOT NULL,
 source VARCHAR(16) NOT NULL,
 reading_count INT NOT NULL,
 analysis_json JSON NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(batch_id,source),
 CONSTRAINT fk_q4_cold_batch FOREIGN KEY(batch_id) REFERENCES batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_publications (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 assessment_id BIGINT NOT NULL,
 source_fingerprint CHAR(64) NOT NULL,
 snapshot_json JSON NOT NULL,
 approved_by VARCHAR(50) NOT NULL,
 request_id CHAR(36) NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL,
 published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_q4_publications(batch_id,id),
 CONSTRAINT fk_q4_pub_batch FOREIGN KEY(batch_id) REFERENCES batches(id),
 CONSTRAINT fk_q4_pub_assess FOREIGN KEY(assessment_id) REFERENCES q4_assessments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS q4_adoptions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 processing_id BIGINT NOT NULL,
 values_json JSON NOT NULL,
 operator VARCHAR(50) NOT NULL,
 note TEXT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_q4_adoptions(processing_id,id),
 CONSTRAINT fk_q4_adoption_batch FOREIGN KEY(batch_id) REFERENCES batches(id),
 CONSTRAINT fk_q4_adoption_process FOREIGN KEY(processing_id) REFERENCES processing_records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
