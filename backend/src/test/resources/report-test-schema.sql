DROP TABLE IF EXISTS inspection_reviews;
DROP TABLE IF EXISTS batch_events;
DROP TABLE IF EXISTS alerts;
DROP TABLE IF EXISTS sensor_readings;
DROP TABLE IF EXISTS processing_records;
DROP TABLE IF EXISTS inspections;
DROP TABLE IF EXISTS batches;

CREATE TABLE batches(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_code VARCHAR(64) UNIQUE,product VARCHAR(50),variety VARCHAR(50),origin VARCHAR(100),supplier VARCHAR(100),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,status VARCHAR(30));
CREATE TABLE inspections(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,image_path VARCHAR(255),result_image_path VARCHAR(255),model_version VARCHAR(50),detections_json CLOB,suggested_grade VARCHAR(20),final_grade VARCHAR(20),reviewer VARCHAR(50),reviewed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE inspection_reviews(id BIGINT AUTO_INCREMENT PRIMARY KEY,inspection_id BIGINT,revision INT,conclusion VARCHAR(40),reviewer VARCHAR(50),remark CLOB,final_grade VARCHAR(20),candidate_reviews CLOB,publish_summary BOOLEAN,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE processing_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,inputs_json CLOB,advice_json CLOB,advice_type VARCHAR(30),adopted_values_json CLOB,operator VARCHAR(50),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE sensor_readings(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,timestamp TIMESTAMP,temperature FLOAT,humidity FLOAT,door_open BOOLEAN,equipment_current FLOAT,source VARCHAR(30));
CREATE TABLE alerts(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,started_at TIMESTAMP,level VARCHAR(20),reason VARCHAR(255),trigger_value VARCHAR(100),status VARCHAR(20),resolved_by VARCHAR(50),resolved_at TIMESTAMP,resolution CLOB);
CREATE TABLE batch_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,event_type VARCHAR(50),event_time TIMESTAMP,summary CLOB,source VARCHAR(50),operator VARCHAR(50),evidence_path VARCHAR(255),visibility VARCHAR(20));

INSERT INTO batches(id,batch_code,product,variety,origin,supplier,status,created_at) VALUES(1,'APPLE-2026-001','苹果','红富士','山东烟台','示例合作社','created','2026-10-01 09:00:00');
INSERT INTO inspections(id,batch_id,model_version,detections_json,created_at,reviewer,reviewed_at) VALUES(10,1,'test-model-nms050','[{"class_id":0},{"class_id":1}]','2026-10-01 10:00:00','operator-test','2026-10-01 10:05:00');
INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,final_grade,publish_summary,created_at) VALUES(10,1,'target_confirmed','operator-test','测试复核',NULL,FALSE,'2026-10-01 10:05:00');
INSERT INTO processing_records(batch_id,inputs_json,advice_json,advice_type,adopted_values_json,operator,created_at) VALUES(1,'{"wash_minutes":5}','{"wash_minutes":6}','rule','{"wash_minutes":6}','operator-b','2026-10-01 11:00:00');
INSERT INTO sensor_readings(batch_id,timestamp,temperature,humidity,door_open,equipment_current,source) VALUES(1,'2026-10-01 12:00:00',3.2,82.0,FALSE,1.5,'demo'),(1,'2026-10-01 12:10:00',4.1,80.0,TRUE,1.6,'demo');
INSERT INTO alerts(batch_id,started_at,level,reason,trigger_value,status,resolved_by,resolved_at,resolution) VALUES(1,'2026-10-01 12:10:00','warning','演示阈值触发','4.1C','resolved','operator-c','2026-10-01 12:20:00','已复核');
INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,visibility) VALUES(1,'入厂','2026-10-01 09:00:00','批次登记','demo','public'),(1,'人工复核','2026-10-01 10:05:00','已保存单张图像人工复核记录','manual','private');
