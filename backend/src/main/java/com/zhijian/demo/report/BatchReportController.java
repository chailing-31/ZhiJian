package com.zhijian.demo.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only internal Demo report aggregation. */
@RestController
public class BatchReportController {
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BatchReportController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/batches/{id}/report")
    public Map<String, Object> report(@PathVariable long id) {
        if (id < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid batch id");

        Map<String, Object> batch = batch(id);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> inspections = inspections(id, warnings);
        List<Map<String, Object>> processing = processing(id, warnings);
        Map<String, Object> coldchain = coldchain(id, warnings);
        List<Map<String, Object>> events = events(id, warnings);

        Map<String, Object> inspectionSection = new LinkedHashMap<>();
        inspectionSection.put("count", inspections.size());
        inspectionSection.put("reviewed_count", inspections.stream()
            .filter(item -> ((Number) item.get("review_revision")).intValue() > 0).count());
        inspectionSection.put("records", inspections);

        Map<String, Object> processingSection = new LinkedHashMap<>();
        processingSection.put("count", processing.size());
        processingSection.put("records", processing);

        @SuppressWarnings("unchecked")
        Map<String, Object> coldSummary = (Map<String, Object>) coldchain.get("summary");
        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("inspection", !inspections.isEmpty());
        availability.put("processing", !processing.isEmpty());
        availability.put("coldchain_readings", ((Number) coldSummary.get("reading_count")).longValue() > 0);
        availability.put("alerts", ((Number) coldchain.get("alert_count")).longValue() > 0);
        availability.put("events", !events.isEmpty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "zhijian.batch.report.v1");
        result.put("scope", "INTERNAL_DEMO_AGGREGATION_ONLY");
        result.put("generated_at", OffsetDateTime.now(DEMO_ZONE).toString());
        result.put("disclaimer", "内部开发演示聚合记录，不是食品安全、监管认证或整批质量合格证明。");
        result.put("batch", batch);
        result.put("inspection", inspectionSection);
        result.put("processing", processingSection);
        result.put("coldchain", coldchain);
        result.put("events", events);
        result.put("data_availability", availability);
        result.put("warnings", warnings);
        return result;
    }

    private Map<String, Object> batch(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT id,batch_code,product,variety,origin,supplier,status,created_at
            FROM batches WHERE id=?
            """, (rs, row) -> batchRow(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");
        return rows.get(0);
    }

    private static Map<String, Object> batchRow(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batch_id", rs.getLong("id"));
        item.put("batch_code", rs.getString("batch_code"));
        item.put("product", rs.getString("product"));
        item.put("variety", rs.getString("variety"));
        item.put("origin", rs.getString("origin"));
        item.put("supplier", rs.getString("supplier"));
        item.put("status", rs.getString("status"));
        item.put("created_at", time(rs.getObject("created_at", LocalDateTime.class)));
        return item;
    }

    private List<Map<String, Object>> inspections(long batchId, List<String> warnings) {
        Map<Long, List<Map<String, Object>>> reviews = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> reviewRows = jdbc.query("""
                SELECT r.inspection_id,r.revision,r.conclusion,r.reviewer,r.remark,
                       r.final_grade,r.publish_summary,r.created_at
                FROM inspection_reviews r
                JOIN inspections i ON i.id=r.inspection_id
                WHERE i.batch_id=?
                ORDER BY r.inspection_id,r.revision
                """, (rs, row) -> {
                    Map<String, Object> review = new LinkedHashMap<>();
                    review.put("inspection_id", rs.getLong("inspection_id"));
                    review.put("revision", rs.getInt("revision"));
                    review.put("conclusion", rs.getString("conclusion"));
                    review.put("reviewer", rs.getString("reviewer"));
                    review.put("remark", rs.getString("remark"));
                    review.put("final_grade", rs.getString("final_grade"));
                    review.put("publish_summary", rs.getBoolean("publish_summary"));
                    review.put("created_at", time(rs.getObject("created_at", LocalDateTime.class)));
                    return review;
                }, batchId);
            for (Map<String, Object> review : reviewRows) {
                long inspectionId = ((Number) review.remove("inspection_id")).longValue();
                reviews.computeIfAbsent(inspectionId, ignored -> new ArrayList<>()).add(review);
            }
        } catch (DataAccessException e) {
            warnings.add("inspection_reviews 不可用；请确认已执行 A3 数据库迁移。报告仍返回 inspections 基础记录。");
        }

        try {
            return jdbc.query("""
                SELECT id,model_version,detections_json,suggested_grade,final_grade,
                       reviewer,reviewed_at,created_at
                FROM inspections WHERE batch_id=? ORDER BY id DESC
                """, (rs, row) -> {
                    long inspectionId = rs.getLong("id");
                    JsonNode detections = jsonNode(rs.getObject("detections_json"));
                    List<Map<String, Object>> history = reviews.getOrDefault(inspectionId, List.of());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("inspection_id", inspectionId);
                    item.put("model_version", rs.getString("model_version"));
                    item.put("candidate_count", detections != null && detections.isArray() ? detections.size() : 0);
                    item.put("suggested_grade", rs.getString("suggested_grade"));
                    item.put("final_grade", rs.getString("final_grade"));
                    item.put("reviewer", rs.getString("reviewer"));
                    item.put("reviewed_at", time(rs.getObject("reviewed_at", LocalDateTime.class)));
                    item.put("created_at", time(rs.getObject("created_at", LocalDateTime.class)));
                    item.put("review_revision", history.size());
                    item.put("reviews", history);
                    item.put("latest_review", history.isEmpty() ? null : history.get(history.size() - 1));
                    item.put("artifact_urls", Map.of(
                        "input", "/api/inspections/" + inspectionId + "/artifacts/input",
                        "result", "/api/inspections/" + inspectionId + "/artifacts/result"));
                    return item;
                }, batchId);
        } catch (DataAccessException e) {
            warnings.add("inspections 表不可用；AI 质检聚合暂不可读取。");
            return List.of();
        }
    }

    private List<Map<String, Object>> processing(long batchId, List<String> warnings) {
        try {
            return jdbc.query("""
                SELECT id,inputs_json,advice_json,advice_type,adopted_values_json,operator,created_at
                FROM processing_records WHERE batch_id=? ORDER BY id DESC
                """, (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("record_id", rs.getLong("id"));
                    item.put("inputs", jsonNode(rs.getObject("inputs_json")));
                    item.put("advice", jsonNode(rs.getObject("advice_json")));
                    item.put("advice_type", rs.getString("advice_type"));
                    item.put("adopted_values", jsonNode(rs.getObject("adopted_values_json")));
                    item.put("operator", rs.getString("operator"));
                    item.put("created_at", time(rs.getObject("created_at", LocalDateTime.class)));
                    return item;
                }, batchId);
        } catch (DataAccessException e) {
            warnings.add("processing_records 表不可用；加工品控聚合暂不可读取。");
            return List.of();
        }
    }

    private Map<String, Object> coldchain(long batchId, List<String> warnings) {
        Map<String, Object> summary = emptyColdchainSummary();
        List<Map<String, Object>> readings = List.of();
        List<Map<String, Object>> alerts = List.of();
        long alertCount = 0;

        try {
            summary = jdbc.query("""
                SELECT COUNT(*) AS reading_count,
                       MIN(timestamp) AS first_time, MAX(timestamp) AS last_time,
                       MIN(temperature) AS min_temperature, MAX(temperature) AS max_temperature,
                       AVG(temperature) AS avg_temperature,
                       MIN(humidity) AS min_humidity, MAX(humidity) AS max_humidity,
                       AVG(humidity) AS avg_humidity,
                       SUM(CASE WHEN door_open = TRUE THEN 1 ELSE 0 END) AS door_open_count
                FROM sensor_readings WHERE batch_id=?
                """, rs -> {
                    rs.next();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reading_count", rs.getLong("reading_count"));
                    item.put("first_time", time(rs.getObject("first_time", LocalDateTime.class)));
                    item.put("last_time", time(rs.getObject("last_time", LocalDateTime.class)));
                    item.put("min_temperature", nullableDouble(rs.getObject("min_temperature")));
                    item.put("max_temperature", nullableDouble(rs.getObject("max_temperature")));
                    item.put("avg_temperature", nullableDouble(rs.getObject("avg_temperature")));
                    item.put("min_humidity", nullableDouble(rs.getObject("min_humidity")));
                    item.put("max_humidity", nullableDouble(rs.getObject("max_humidity")));
                    item.put("avg_humidity", nullableDouble(rs.getObject("avg_humidity")));
                    item.put("door_open_count", rs.getLong("door_open_count"));
                    return item;
                }, batchId);

            readings = jdbc.query("""
                SELECT id,timestamp,temperature,humidity,door_open,equipment_current,source
                FROM sensor_readings WHERE batch_id=? ORDER BY timestamp DESC,id DESC LIMIT 200
                """, (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reading_id", rs.getLong("id"));
                    item.put("timestamp", time(rs.getObject("timestamp", LocalDateTime.class)));
                    item.put("temperature", nullableDouble(rs.getObject("temperature")));
                    item.put("humidity", nullableDouble(rs.getObject("humidity")));
                    Object door = rs.getObject("door_open");
                    item.put("door_open", door == null ? null : rs.getBoolean("door_open"));
                    item.put("equipment_current", nullableDouble(rs.getObject("equipment_current")));
                    item.put("source", rs.getString("source"));
                    return item;
                }, batchId);
        } catch (DataAccessException e) {
            warnings.add("sensor_readings 表不可用；冷链读数聚合暂不可读取。");
        }

        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM alerts WHERE batch_id=?", Long.class, batchId);
            alertCount = count == null ? 0 : count;
            alerts = jdbc.query("""
                SELECT id,started_at,level,reason,trigger_value,status,resolved_by,resolved_at,resolution
                FROM alerts WHERE batch_id=? ORDER BY started_at DESC,id DESC LIMIT 200
                """, (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("alert_id", rs.getLong("id"));
                    item.put("started_at", time(rs.getObject("started_at", LocalDateTime.class)));
                    item.put("level", rs.getString("level"));
                    item.put("reason", rs.getString("reason"));
                    item.put("trigger_value", rs.getString("trigger_value"));
                    item.put("status", rs.getString("status"));
                    item.put("resolved_by", rs.getString("resolved_by"));
                    item.put("resolved_at", time(rs.getObject("resolved_at", LocalDateTime.class)));
                    item.put("resolution", rs.getString("resolution"));
                    return item;
                }, batchId);
        } catch (DataAccessException e) {
            warnings.add("alerts 表不可用；冷链告警聚合暂不可读取。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("readings", readings);
        result.put("readings_truncated", ((Number) summary.get("reading_count")).longValue() > readings.size());
        result.put("alert_count", alertCount);
        result.put("alerts", alerts);
        result.put("alerts_truncated", alertCount > alerts.size());
        return result;
    }

    private List<Map<String, Object>> events(long batchId, List<String> warnings) {
        try {
            return jdbc.query("""
                SELECT id,event_type,event_time,summary,source,operator,evidence_path,visibility
                FROM batch_events WHERE batch_id=? ORDER BY event_time,id
                """, (rs, row) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("event_id", rs.getLong("id"));
                    event.put("event_type", rs.getString("event_type"));
                    event.put("event_time", time(rs.getObject("event_time", LocalDateTime.class)));
                    event.put("summary", rs.getString("summary"));
                    event.put("source", rs.getString("source"));
                    event.put("operator", rs.getString("operator"));
                    event.put("evidence_path", rs.getString("evidence_path"));
                    event.put("visibility", rs.getString("visibility"));
                    return event;
                }, batchId);
        } catch (DataAccessException e) {
            warnings.add("batch_events 表不可用；事件时间线暂不可读取。");
            return List.of();
        }
    }

    private static Map<String, Object> emptyColdchainSummary() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reading_count", 0L);
        item.put("first_time", null);
        item.put("last_time", null);
        item.put("min_temperature", null);
        item.put("max_temperature", null);
        item.put("avg_temperature", null);
        item.put("min_humidity", null);
        item.put("max_humidity", null);
        item.put("avg_humidity", null);
        item.put("door_open_count", 0L);
        return item;
    }

    private JsonNode jsonNode(Object raw) {
        if (raw == null) return null;
        try {
            if (raw instanceof byte[] bytes) return json.readTree(bytes);
            if (raw instanceof Clob clob) return json.readTree(clob.getSubString(1, Math.toIntExact(clob.length())));
            String value = String.valueOf(raw);
            return value.isBlank() ? null : json.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String time(LocalDateTime value) {
        return value == null ? null : value.atZone(DEMO_ZONE).toOffsetDateTime().toString();
    }
}
