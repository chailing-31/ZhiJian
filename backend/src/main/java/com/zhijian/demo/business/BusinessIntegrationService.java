package com.zhijian.demo.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/** Persistence and protocol boundary for the independent B service. */
@Service
public class BusinessIntegrationService {
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String[] SOURCES = {"simulation", "sensor"};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;

    public BusinessIntegrationService(
        JdbcTemplate jdbc,
        ObjectMapper json,
        org.springframework.transaction.PlatformTransactionManager transactionManager,
        @Value("${zhijian.business.base-url:http://127.0.0.1:8002}") String baseUrl
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public Map<String, Object> ready() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "business-integration");
        result.put("base_url", baseUrl);
        try {
            jdbc.queryForList("SELECT id FROM coldchain_episode_records LIMIT 0");
            result.put("database_ready", true);
        } catch (DataAccessException error) {
            result.put("database_ready", false);
        }
        try {
            JsonNode response = business("GET", "/health", null);
            result.put("business_ready", response.path("status").asText("").equals("ok"));
        } catch (BusinessIntegrationFault error) {
            result.put("business_ready", false);
            result.put("business_error", error.getMessage());
        }
        result.put("ready", Boolean.TRUE.equals(result.get("database_ready")) && Boolean.TRUE.equals(result.get("business_ready")));
        return result;
    }

    public Map<String, Object> processingAdvice(long batchId, JsonNode input) {
        String batchCode = batchCode(batchId);
        String grade = text(input, "grade", true);
        if (!List.of("A", "B", "C", "REJECT").contains(grade)) {
            throw fault(400, "INVALID_GRADE", "grade 必须是 A/B/C/REJECT。");
        }
        Double temperature = optionalNumber(input,"temperature");
        Double humidity = optionalNumber(input,"humidity");
        if(!"REJECT".equals(grade)) {
            temperature=finiteNumber(input,"temperature",true);
            humidity=finiteNumber(input,"humidity",true);
        }
        if (humidity!=null&&(humidity < 0 || humidity > 100)) throw fault(400, "INVALID_HUMIDITY", "humidity 必须在 0—100 之间。");
        Double materialTemperature = optionalNumber(input, "material_temperature");
        String operator = optionalText(input, "operator");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("batch_id", batchId);
        request.put("grade", grade);
        if(temperature!=null)request.put("temperature", temperature);
        if(humidity!=null)request.put("humidity", humidity);
        if (materialTemperature != null) request.put("material_temperature", materialTemperature);
        JsonNode advice = "REJECT".equals(grade)&&(temperature==null||humidity==null)?json.valueToTree(Map.of("batch_id",batchId,"grade","REJECT","status","blocked",
            "advice_type","quality_hold","rule_version","initial-reject-hold-v5.1","requires_confirmation",true,
            "reason","REJECT 初评或修正等级，暂停正常流转，请登记实际处置或检查修正。")):
            business("POST", "/business/process-advice", request);

        String inputsJson = writeJson(request);
        String adviceJson = writeJson(advice);
        Long recordId = transaction.execute(status -> {
            jdbc.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE", batchId);
            var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                    "INSERT INTO processing_records(batch_id,inputs_json,advice_json,advice_type,operator) VALUES(?,?,?,?,?)",
                    new String[]{"id"});
                ps.setLong(1, batchId); ps.setString(2, inputsJson); ps.setString(3, adviceJson);
                ps.setString(4, advice.path("advice_type").asText("rule")); ps.setString(5, operator);
                return ps;
            }, key);
            long id = key.getKey().longValue();
            jdbc.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,visibility) VALUES(?, '加工建议', CURRENT_TIMESTAMP, ?, 'business-rules', ?, 'private')",
                batchId, "已保存规则建议，状态=" + advice.path("status").asText("unknown") + "；仍需人工确认。", operator);
            return id;
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record_id", recordId);
        result.put("batch_id", batchId);
        result.put("batch_code", batchCode);
        result.put("inputs", request);
        result.put("advice", advice);
        result.put("persisted", true);
        return result;
    }

    public Map<String, Object> adoptProcessing(long recordId, JsonNode input) {
        JsonNode values = input == null ? null : input.get("adopted_values");
        if (values == null || !values.isObject()) throw fault(400, "INVALID_ADOPTED_VALUES", "adopted_values 必须是对象。");
        String operator = text(input, "operator", true);
        if (operator.length() > 50) throw fault(400, "INVALID_OPERATOR", "操作员最多 50 个字符。");
        text(values, "pre_cooling_time", true);
        text(values, "washing_pressure", true);
        String note = optionalText(input, "note");
        return transaction.execute(tx -> {
        Map<String, Object> sourceRecord = one("SELECT batch_id FROM processing_records WHERE id=?", recordId);
        long sourceBatch = ((Number)sourceRecord.get("batch_id")).longValue();
        jdbc.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE", sourceBatch);
        Map<String, Object> record = one("SELECT id,batch_id,advice_json,inputs_json FROM processing_records WHERE id=? FOR UPDATE", recordId);
        com.zhijian.demo.workspace.QualitySourceGuard.verify(jdbc, sourceBatch, readJson(record.get("inputs_json")));
        com.zhijian.demo.batchflow.BatchLedger.checkAdoptionRequest(jdbc,json,sourceBatch,recordId,input);
        JsonNode savedAdvice = readJson(record.get("advice_json"));
        if (savedAdvice == null || !"suggested".equals(savedAdvice.path("status").asText())) {
            throw fault(409, "ADVICE_BLOCKED", "该建议不是 suggested（含 blocked/manual_review），不能绕过复核保存工艺采用值。");
        }
        String valuesJson = writeJson(values);
        jdbc.update("UPDATE processing_records SET adopted_values_json=?, operator=? WHERE id=?", valuesJson, operator, recordId);
        com.zhijian.demo.batchflow.BatchLedger.recordAdoption(jdbc,sourceBatch,recordId,valuesJson,operator,note);
        jdbc.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,visibility) VALUES(?, '加工采用', CURRENT_TIMESTAMP, ?, 'operator', ?, 'private')",
            ((Number) record.get("batch_id")).longValue(), note == null ? "已保存人工采用值。" : note, operator);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record_id", recordId);
        result.put("batch_id", record.get("batch_id"));
        result.put("adopted_values", values);
        result.put("operator", operator);
        result.put("note", note);
        result.put("persisted", true);
        return result;
        });
    }

    public Map<String, Object> processingHistory(long batchId) {
        batchCode(batchId);
        List<Map<String, Object>> records = jdbc.query("SELECT id,inputs_json,advice_json,adopted_values_json,operator,created_at FROM processing_records WHERE batch_id=? ORDER BY id DESC LIMIT 200",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("record_id", rs.getLong("id"));
                row.put("inputs", readJson(rs.getString("inputs_json")));
                row.put("advice", readJson(rs.getString("advice_json")));
                row.put("adopted_values", readJson(rs.getString("adopted_values_json")));
                row.put("operator", rs.getString("operator"));
                row.put("created_at", dbTime(rs.getTimestamp("created_at")));
                return row;
            }, batchId);
        return Map.of("batch_id", batchId, "records", records);
    }

    public Map<String, Object> sensorReading(long batchId, JsonNode input) {
        batchCode(batchId);
        String source = text(input, "source", true);
        if (!Arrays.asList(SOURCES).contains(source)) throw fault(400, "INVALID_SOURCE", "source 必须是 simulation 或 sensor。");
        OffsetDateTime timestamp = parseTime(text(input, "timestamp", true));
        double temperature = finiteNumber(input, "temperature", true);
        double humidity = finiteNumber(input, "humidity", true);
        if (humidity < 0 || humidity > 100) throw fault(400, "INVALID_HUMIDITY", "humidity 必须在 0—100 之间。");
        Double current = optionalNumber(input, "equipment_current");
        if (current != null && current < 0) throw fault(400, "INVALID_CURRENT", "equipment_current 不能为负数。");
        if (input.hasNonNull("door_open") && !input.get("door_open").isBoolean()) throw fault(400, "INVALID_DOOR_OPEN", "door_open 必须是布尔值或 null。");
        if (input.has("config") && !input.get("config").isObject()) throw fault(400, "INVALID_CONFIG", "config 必须是对象。");
        Boolean doorOpen = input.hasNonNull("door_open") ? input.get("door_open").booleanValue() : null;
        LocalDateTime dbTime = timestamp.atZoneSameInstant(DEMO_ZONE).toLocalDateTime();
        if (dbTime.getYear() < 1000 || dbTime.getYear() > 9999 || dbTime.getNano() != 0) throw fault(400, "INVALID_TIMESTAMP", "时间须在 MySQL 日期范围内，并精确到整秒。");
        if (!Float.isFinite((float) temperature) || (current != null && !Float.isFinite(current.floatValue()))) throw fault(400, "INVALID_NUMBER_RANGE", "数值超出数据库可保存范围。");

        return transaction.execute(tx -> {
        // Serialize writes for a batch, including episode upserts, across server instances.
        one("SELECT id FROM batches WHERE id=? FOR UPDATE", batchId);
        List<Map<String, Object>> existingReadings = jdbc.query("SELECT temperature,humidity,door_open,equipment_current FROM sensor_readings WHERE batch_id=? AND source=? AND timestamp=? LIMIT 1",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("temperature", rs.getFloat("temperature")); row.put("humidity", rs.getFloat("humidity"));
                row.put("door_open", rs.getObject("door_open") == null ? null : rs.getBoolean("door_open"));
                row.put("equipment_current", rs.getObject("equipment_current") == null ? null : rs.getFloat("equipment_current"));
                return row;
            }, batchId, source, Timestamp.valueOf(dbTime));
        boolean duplicate = !existingReadings.isEmpty();
        if (duplicate) {
            Map<String, Object> prior = existingReadings.get(0);
            if (!Objects.equals(prior.get("temperature"), (float) temperature) || !Objects.equals(prior.get("humidity"), (float) humidity)
                || !Objects.equals(prior.get("door_open"), doorOpen) || !Objects.equals(prior.get("equipment_current"), current == null ? null : current.floatValue())) {
                throw fault(409, "READING_CONFLICT", "同一批次、来源和时刻已有不同读数，不能覆盖。");
            }
        }
        if (!duplicate) {
            jdbc.update("INSERT INTO sensor_readings(batch_id,timestamp,temperature,humidity,door_open,equipment_current,source) VALUES(?,?,?,?,?,?,?)",
                batchId, Timestamp.valueOf(dbTime), temperature, humidity, doorOpen, current, source);
        }

        List<Map<String, Object>> history = jdbc.query("SELECT timestamp,temperature,humidity,door_open,equipment_current,source FROM sensor_readings WHERE batch_id=? AND source=? ORDER BY timestamp,id LIMIT 10001",
            (rs, row) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timestamp", rs.getTimestamp("timestamp").toLocalDateTime().atZone(DEMO_ZONE).toOffsetDateTime().toString());
                item.put("temperature", rs.getDouble("temperature"));
                item.put("humidity", rs.getDouble("humidity"));
                Object door = rs.getObject("door_open"); item.put("door_open", door == null ? null : rs.getBoolean("door_open"));
                Object amp = rs.getObject("equipment_current"); item.put("equipment_current", amp == null ? null : rs.getDouble("equipment_current"));
                item.put("source", rs.getString("source"));
                return item;
            }, batchId, source);
        if (history.size() > 10000) throw fault(409, "HISTORY_TOO_LARGE", "该批次冷链历史超过单次规则服务上限。");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("batch_id", batchId); request.put("readings", history);
        if (input != null && input.has("config") && input.get("config").isObject()) request.put("config", input.get("config"));
        JsonNode analysis = business("POST", "/coldchain/check", request);
        List<Map<String, Object>> episodes = persistEpisodes(batchId, source, analysis, input == null ? null : input.get("config"));
        com.zhijian.demo.batchflow.BatchLedger.saveCold(jdbc,batchId,source,history.size(),analysis);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch_id", batchId); result.put("source", source); result.put("timestamp", timestamp.toString());
        result.put("duplicate", duplicate); result.put("reading_count", history.size()); result.put("analysis", analysis);
        result.put("episodes", episodes); result.put("persisted", true);
        return result;
        });
    }

    public Map<String, Object> coldchain(long batchId) {
        batchCode(batchId);
        Map<String, Object> summary = jdbc.query("SELECT COUNT(*) reading_count, MIN(timestamp) first_time, MAX(timestamp) last_time, MIN(temperature) min_temperature, MAX(temperature) max_temperature, AVG(temperature) avg_temperature, MIN(humidity) min_humidity, MAX(humidity) max_humidity, AVG(humidity) avg_humidity FROM sensor_readings WHERE batch_id=?",
            rs -> { rs.next(); Map<String, Object> value = new LinkedHashMap<>(); value.put("reading_count", rs.getLong("reading_count")); value.put("first_time", dbTime(rs.getTimestamp("first_time"))); value.put("last_time", dbTime(rs.getTimestamp("last_time"))); value.put("min_temperature", nullable(rs.getObject("min_temperature"))); value.put("max_temperature", nullable(rs.getObject("max_temperature"))); value.put("avg_temperature", nullable(rs.getObject("avg_temperature"))); value.put("min_humidity", nullable(rs.getObject("min_humidity"))); value.put("max_humidity", nullable(rs.getObject("max_humidity"))); value.put("avg_humidity", nullable(rs.getObject("avg_humidity"))); return value; }, batchId);
        List<Map<String, Object>> readings = jdbc.query("SELECT id,timestamp,temperature,humidity,door_open,equipment_current,source FROM sensor_readings WHERE batch_id=? ORDER BY timestamp DESC,id DESC LIMIT 200",
            (rs, row) -> { Map<String, Object> v = new LinkedHashMap<>(); v.put("reading_id", rs.getLong("id")); v.put("timestamp", dbTime(rs.getTimestamp("timestamp"))); v.put("temperature", rs.getDouble("temperature")); v.put("humidity", rs.getDouble("humidity")); Object d=rs.getObject("door_open"); v.put("door_open", d == null ? null : rs.getBoolean("door_open")); Object c=rs.getObject("equipment_current"); v.put("equipment_current", c == null ? null : rs.getDouble("equipment_current")); v.put("source", rs.getString("source")); return v; }, batchId);
        List<Map<String, Object>> alerts = jdbc.query("SELECT id,started_at,level,reason,trigger_value,status,resolved_by,resolved_at,resolution FROM alerts WHERE batch_id=? ORDER BY started_at DESC,id DESC LIMIT 200",
            (rs, row) -> { Map<String, Object> v = new LinkedHashMap<>(); v.put("alert_id", rs.getLong("id")); v.put("started_at", dbTime(rs.getTimestamp("started_at"))); v.put("level", rs.getString("level")); v.put("reason", rs.getString("reason")); v.put("trigger_value", rs.getString("trigger_value")); v.put("status", rs.getString("status")); v.put("resolved_by", rs.getString("resolved_by")); v.put("resolved_at", dbTime(rs.getTimestamp("resolved_at"))); v.put("resolution", rs.getString("resolution")); return v; }, batchId);
        List<Map<String, Object>> episodes = jdbc.query("SELECT id,episode_key,source,alert_id,started_at,triggered_at,last_observed_at,recovered_at,end_reason,trigger_value,observed_minutes,peak_temperature,level,current_status,reason,rule_version FROM coldchain_episode_records WHERE batch_id=? ORDER BY started_at DESC,id DESC LIMIT 200",
            (rs, row) -> { Map<String, Object> v=new LinkedHashMap<>(); v.put("episode_id", rs.getLong("id")); v.put("episode_key", rs.getString("episode_key")); v.put("source", rs.getString("source")); v.put("alert_id", rs.getObject("alert_id")); v.put("started_at", dbTime(rs.getTimestamp("started_at"))); v.put("triggered_at", dbTime(rs.getTimestamp("triggered_at"))); v.put("last_observed_at", dbTime(rs.getTimestamp("last_observed_at"))); v.put("recovered_at", dbTime(rs.getTimestamp("recovered_at"))); v.put("end_reason", rs.getString("end_reason")); v.put("trigger_value", rs.getObject("trigger_value")); v.put("observed_minutes", rs.getObject("observed_minutes")); v.put("peak_temperature", rs.getObject("peak_temperature")); v.put("level", rs.getString("level")); v.put("status", rs.getString("current_status")); v.put("reason", rs.getString("reason")); v.put("rule_version", rs.getString("rule_version")); return v; }, batchId);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("batch_id", batchId); result.put("summary", summary); result.put("readings", readings); result.put("episodes", episodes); result.put("alerts", alerts); result.put("alert_count", alerts.size()); result.put("readings_truncated", ((Number) summary.get("reading_count")).longValue() > readings.size()); return result;
    }

    public Map<String, Object> resolveAlert(long alertId, JsonNode input) {
        String resolvedBy = text(input, "resolved_by", true);
        String resolution = text(input, "resolution", true);
        if (resolvedBy.length() > 50) throw fault(400, "INVALID_RESOLVED_BY", "处置人最多 50 个字符。");
        return transaction.execute(tx -> {
        Map<String, Object> alertSource = one("SELECT batch_id FROM alerts WHERE id=?", alertId);
        jdbc.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE", alertSource.get("batch_id"));
        Map<String, Object> alert = one("SELECT id,batch_id,status FROM alerts WHERE id=? FOR UPDATE", alertId);
        if ("resolved".equals(alert.get("status"))) return Map.of("alert_id", alertId, "status", "resolved", "persisted", true, "duplicate", true);
        jdbc.update("UPDATE alerts SET status='resolved',resolved_by=?,resolved_at=CURRENT_TIMESTAMP,resolution=? WHERE id=?", resolvedBy, resolution, alertId);
        jdbc.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,visibility) VALUES(?, '冷链处置', CURRENT_TIMESTAMP, ?, 'operator', ?, 'private')",
            ((Number) alert.get("batch_id")).longValue(), resolution, resolvedBy);
        return Map.of("alert_id", alertId, "batch_id", alert.get("batch_id"), "status", "resolved", "resolved_by", resolvedBy, "resolution", resolution, "persisted", true);
        });
    }

    private List<Map<String, Object>> persistEpisodes(long batchId, String source, JsonNode analysis, JsonNode config) {
        String ruleVersion = analysis.path("rule_version").asText("unknown");
        // Hash effective configuration, so omitted defaults and explicit defaults match.
        JsonNode effectiveConfig = analysis.has("config") ? analysis.get("config") : config;
        String configJson = effectiveConfig == null ? "{}" : writeJson(json.convertValue(effectiveConfig, TreeMap.class));
        List<Map<String, Object>> persisted = new ArrayList<>();
        for (JsonNode episode : analysis.path("episodes")) {
            String started = text(episode, "started_at", true);
            String key = sha256(batchId + "|" + source + "|" + ruleVersion + "|" + started + "|" + configJson);
            LocalDateTime startedDb = parseTime(started).atZoneSameInstant(DEMO_ZONE).toLocalDateTime();
            LocalDateTime triggeredDb = optionalTime(episode, "triggered_at");
            LocalDateTime lastDb = optionalTime(episode, "last_observed_at");
            LocalDateTime recoveredDb = optionalTime(episode, "recovered_at");
            String endReason = text(episode, "end_reason", true);
            String level = "HIGH";
            String status = endReason.equals("ongoing") ? "active" : "observed";
            String reason = "演示规则持续超温区段；开始=" + started + "；状态=" + endReason + "；峰值=" + number(episode, "peak_temperature") + "℃。";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("episode_key", key); row.put("started_at", started); row.put("end_reason", endReason); row.put("status", status);
            // JdbcTemplate.queryForObject throws when the episode is new. Query as a list so
            // the first observation can take the insert path without an exception.
            List<Long> existingIds = jdbc.query("SELECT id FROM coldchain_episode_records WHERE episode_key=? LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"), key);
            Long existing = existingIds.isEmpty() ? null : existingIds.get(0);
            Long alertId;
            if (existing == null) {
                jdbc.update("INSERT INTO coldchain_episode_records(batch_id,source,episode_key,started_at,triggered_at,last_observed_at,recovered_at,end_reason,trigger_value,observed_minutes,peak_temperature,level,current_status,reason,rule_version,config_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    batchId, source, key, Timestamp.valueOf(startedDb), timestamp(triggeredDb), timestamp(lastDb), timestamp(recoveredDb), endReason, number(episode, "trigger_value"), number(episode, "observed_minutes"), number(episode, "peak_temperature"), level, status, reason, ruleVersion, configJson);
                var alertKey = new org.springframework.jdbc.support.GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var ps = connection.prepareStatement("INSERT INTO alerts(batch_id,started_at,level,reason,trigger_value,status) VALUES(?,?,?,?,?,?)", new String[]{"id"});
                    ps.setLong(1, batchId); ps.setTimestamp(2, Timestamp.valueOf(triggeredDb == null ? startedDb : triggeredDb));
                    ps.setString(3, level); ps.setString(4, reason); ps.setString(5, String.valueOf(number(episode, "trigger_value"))); ps.setString(6, status);
                    return ps;
                }, alertKey);
                alertId = Objects.requireNonNull(alertKey.getKey()).longValue();
                jdbc.update("UPDATE coldchain_episode_records SET alert_id=? WHERE episode_key=?", alertId, key);
                jdbc.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,visibility) VALUES(?, '冷链告警', CURRENT_TIMESTAMP, ?, 'business-rules', 'private')", batchId, reason + "（规则区段已保存，来源=" + source + "）");
            } else {
                alertId = jdbc.queryForObject("SELECT alert_id FROM coldchain_episode_records WHERE episode_key=?", Long.class, key);
                jdbc.update("UPDATE coldchain_episode_records SET triggered_at=?,last_observed_at=?,recovered_at=?,end_reason=?,trigger_value=?,observed_minutes=?,peak_temperature=?,level=?,current_status=?,reason=?,config_json=? WHERE episode_key=?",
                    timestamp(triggeredDb), timestamp(lastDb), timestamp(recoveredDb), endReason, number(episode, "trigger_value"), number(episode, "observed_minutes"), number(episode, "peak_temperature"), level, status, reason, configJson, key);
                if (alertId != null) jdbc.update("UPDATE alerts SET level=?,reason=?,trigger_value=?,status=? WHERE id=? AND status <> 'resolved'", level, reason, String.valueOf(number(episode, "trigger_value")), status, alertId);
            }
            row.put("alert_id", alertId); row.put("rule_version", ruleVersion); persisted.add(row);
        }
        return persisted;
    }

    private JsonNode business(String method, String path, Object payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
            if ("GET".equals(method)) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                int status = response.statusCode() == 422 ? 400 : response.statusCode() == 503 ? 503 : 502;
                throw fault(status, status == 400 ? "BUSINESS_VALIDATION" : "BUSINESS_UNAVAILABLE", response.body());
            }
            return json.readTree(response.body());
        } catch (BusinessIntegrationFault error) { throw error;
        } catch (Exception error) { throw fault(503, "BUSINESS_UNAVAILABLE", "无法连接 B 规则服务，请确认其已在 " + baseUrl + " 启动。"); }
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw fault(404, "RECORD_NOT_FOUND", "记录不存在。");
        return rows.get(0);
    }

    private String batchCode(long id) { return String.valueOf(one("SELECT batch_code FROM batches WHERE id=?", id).get("batch_code")); }
    private static BusinessIntegrationFault fault(int status, String code, String message) { return new BusinessIntegrationFault(status, code, message); }
    private static String text(JsonNode node, String field, boolean required) { String value = optionalText(node, field); if (required && (value == null || value.isBlank())) throw fault(400, "INVALID_" + field.toUpperCase(), field + " 为必填字段。"); return value; }
    private static String optionalText(JsonNode node, String field) { if (node == null || !node.hasNonNull(field)) return null; String value = node.get(field).asText(); return value.isBlank() ? null : value.trim(); }
    private static double finiteNumber(JsonNode node, String field, boolean required) { if (node == null || !node.has(field) || node.get(field).isNull()) { if (required) throw fault(400, "INVALID_" + field.toUpperCase(), field + " 为必填字段。"); return Double.NaN; } if (!node.get(field).isNumber()) throw fault(400, "INVALID_" + field.toUpperCase(), field + " 必须是有限数值。"); double value=node.get(field).doubleValue(); if (!Double.isFinite(value)) throw fault(400, "INVALID_" + field.toUpperCase(), field + " 必须是有限数值。"); return value; }
    private static Double optionalNumber(JsonNode node, String field) { if (node == null || !node.has(field) || node.get(field).isNull()) return null; return finiteNumber(node, field, true); }
    private static OffsetDateTime parseTime(String value) { try { OffsetDateTime parsed=OffsetDateTime.parse(value); return parsed; } catch (Exception error) { throw fault(400, "INVALID_TIMESTAMP", "timestamp 必须是带时区的 ISO 8601 时间。"); } }
    private static LocalDateTime optionalTime(JsonNode node, String field) { String value=optionalText(node, field); return value == null ? null : parseTime(value).atZoneSameInstant(DEMO_ZONE).toLocalDateTime(); }
    private static Timestamp timestamp(LocalDateTime value) { return value == null ? null : Timestamp.valueOf(value); }
    private static String dbTime(Timestamp value) { return value == null ? null : value.toLocalDateTime().atZone(DEMO_ZONE).toOffsetDateTime().toString(); }
    private static Object nullable(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : null; }
    private static Double number(JsonNode node, String field) { return node != null && node.has(field) && !node.get(field).isNull() ? node.get(field).doubleValue() : null; }
    private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw fault(500, "JSON_SERIALIZATION_FAILED", "无法序列化业务记录。"); } }
    private JsonNode readJson(Object value) { if (value == null) return null; try { JsonNode parsed = value instanceof byte[] b ? json.readTree(b) : value instanceof java.sql.Clob c ? json.readTree(c.getSubString(1, Math.toIntExact(c.length()))) : json.readTree(value.toString()); return parsed.isTextual() ? json.readTree(parsed.textValue()) : parsed; } catch (Exception error) { throw fault(500, "JSON_RECORD_INVALID", "已保存的业务记录格式不正确。"); } }
    private static String sha256(String value) { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:digest) out.append(String.format("%02x",b)); return out.toString(); } catch(Exception error){ throw new IllegalStateException(error); } }
}
