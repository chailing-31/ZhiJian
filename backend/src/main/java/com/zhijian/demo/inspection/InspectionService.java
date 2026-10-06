package com.zhijian.demo.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
@Profile("inspection")
public class InspectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiBridge ai;
    private final ArtifactStore store;
    private final TransactionTemplate transaction;
    // Match the local single-worker CPU model. Do not queue many CPU inferences.
    private final Semaphore inferenceSlot = new Semaphore(1);
    public InspectionService(JdbcTemplate jdbc, ObjectMapper json, AiBridge ai, ArtifactStore store, PlatformTransactionManager tm) {
        this.jdbc = jdbc; this.json = json; this.ai = ai; this.store = store; this.transaction = new TransactionTemplate(tm);
    }
    public Map<String, Object> ready() {
        boolean schema;
        try { checkSchema(); schema = true; } catch (InspectionFault e) { schema = false; }
        boolean model = ai.ready();
        return Map.of("database_ready", schema, "model_ready", model, "ready", schema && model,
            "message", !schema ? "请确认 MySQL 已启动并执行 A3 两张附加表迁移。" : !model ? "请启动已加载模型的 8001 服务。" : "可进行内部质检联调，非模型性能认证。");
    }
    private void checkSchema() {
        try {
            jdbc.queryForList("SELECT inspection_id,request_id,image_sha256,prediction_id,response_json,created_at FROM inspection_payloads LIMIT 0");
            jdbc.queryForList("SELECT id,inspection_id,revision,conclusion,reviewer,remark,final_grade,candidate_reviews,publish_summary,created_at FROM inspection_reviews LIMIT 0");
        } catch (DataAccessException e) { throw new InspectionFault(503, "INSPECTION_SCHEMA_NOT_READY", "请检查数据库连接，并先执行 database/migrations/20261001_A3_inspection_integration.sql。"); }
    }
    private String batch(long id) {
        if (id < 1) throw new InspectionFault(400, "INVALID_BATCH", "批次 ID 必须是实际数值主键。");
        var codes = jdbc.queryForList("SELECT batch_code FROM batches WHERE id=?", String.class, id);
        if (codes.isEmpty()) throw new InspectionFault(404, "BATCH_NOT_FOUND", "批次不存在。");
        return codes.get(0);
    }
    public List<Map<String, Object>> list(long batchId) {
        checkSchema(); batch(batchId);
        return jdbc.query("""
            SELECT i.id,i.model_version,i.created_at,p.prediction_id,
              (SELECT COUNT(*) FROM inspection_reviews r WHERE r.inspection_id=i.id) AS review_revision
            FROM inspections i JOIN inspection_payloads p ON i.id=p.inspection_id
            WHERE i.batch_id=? ORDER BY i.id DESC LIMIT 100
            """, (rs, row) -> Map.<String, Object>of("inspection_id", rs.getLong("id"), "model_version", rs.getString("model_version"),
                "created_at", time(rs.getObject("created_at", LocalDateTime.class)), "prediction_id", rs.getString("prediction_id"),
                "review_revision", rs.getInt("review_revision")), batchId);
    }
    public Map<String, Object> create(long batchId, String requestKey, MultipartFile file) {
        checkSchema(); String code = batch(batchId);
        String key;
        try { key = UUID.fromString(requestKey).toString(); } catch (Exception e) { throw new InspectionFault(400, "INVALID_REQUEST_KEY", "缺少有效的 Idempotency-Key，请重新选择图片。"); }
        if (file == null || file.isEmpty()) throw new InspectionFault(400, "EMPTY_IMAGE", "请选择图片。");
        if (file.getSize() > 10L * 1024 * 1024) throw new InspectionFault(413, "IMAGE_TOO_LARGE", "图片不能超过 10 MiB。");
        String mime = file.getContentType();
        if (mime == null || !Set.of("image/jpeg", "image/png", "image/webp").contains(mime)) throw new InspectionFault(422, "INVALID_IMAGE", "只接受 JPG、PNG、WebP 图片。");
        byte[] bytes;
        try (var in = file.getInputStream()) { bytes = in.readNBytes(10 * 1024 * 1024 + 1); }
        catch (IOException e) { throw new InspectionFault(400, "IMAGE_READ_FAILED", "无法读取上传图片。"); }
        if (bytes.length > 10 * 1024 * 1024) throw new InspectionFault(413, "IMAGE_TOO_LARGE", "图片过大。");
        String hash = sha(bytes);
        Long previous = existingRequest(key, batchId, hash);
        if (previous != null) return detail(previous);
        if (!inferenceSlot.tryAcquire()) throw new InspectionFault(503, "AI_BUSY", "已有检测正在处理，请等待结束后刷新历史记录。");
        try {
            previous = existingRequest(key, batchId, hash);
            if (previous != null) return detail(previous);
            JsonNode prediction = ai.predict(batchId, code, bytes, mime);
            UUID predictionId = PredictionValidator.validate(prediction, batchId, code, hash);
            store.capture(predictionId, ai);
            Long id;
            try {
                id = transaction.execute(status -> {
                    long inspection = insert("""
                        INSERT INTO inspections(batch_id,image_path,result_image_path,model_version,detections_json,suggested_grade)
                        VALUES(?,?,?,?,?,?)
                        """, batchId, predictionId + "/input.png", predictionId + "/result.png",
                        prediction.path("model_version").asText(), encode(prediction.path("detections")),
                        prediction.path("suggested_grade").isNull() ? null : prediction.path("suggested_grade").asText());
                    jdbc.update("INSERT INTO inspection_payloads(inspection_id,request_id,image_sha256,prediction_id,response_json) VALUES(?,?,?,?,?)",
                        inspection, key, hash, predictionId.toString(), encode(prediction));
                    jdbc.update("""
                        INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,evidence_path,visibility)
                        VALUES(?,'AI质检',CURRENT_TIMESTAMP,?,'model',?,'private')
                        """, batchId, "单张图片推理已保存，输出 " + prediction.path("detections").size() + " 个候选框，待人工复核；不是批次合格结论。",
                        "/inspections/" + inspection + "/artifacts/result");
                    return inspection;
                });
            } catch (DuplicateKeyException e) {
                Long known = existingRequest(key, batchId, hash);
                if (known == null) throw new InspectionFault(409, "PREDICTION_CONFLICT", "预测记录冲突，未重复保存，请刷新历史。");
                id = known;
            }
            return detail(Objects.requireNonNull(id));
        } finally { inferenceSlot.release(); }
    }
    private Long existingRequest(String key, long batchId, String hash) {
        var rows = jdbc.queryForList("SELECT p.inspection_id,p.image_sha256,i.batch_id FROM inspection_payloads p JOIN inspections i ON p.inspection_id=i.id WHERE p.request_id=?", key);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        if (((Number) r.get("batch_id")).longValue() != batchId || !hash.equals(r.get("image_sha256")))
            throw new InspectionFault(409, "REQUEST_KEY_REUSED", "同一个请求编号不能用于不同批次或图片。");
        return ((Number) r.get("inspection_id")).longValue();
    }
    private Map<String, Object> row(long id, boolean lock) {
        var rows = jdbc.queryForList("""
            SELECT i.id,i.batch_id,i.created_at,p.prediction_id,p.response_json
            FROM inspections i JOIN inspection_payloads p ON i.id=p.inspection_id WHERE i.id=?
            """ + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new InspectionFault(404, "INSPECTION_NOT_FOUND", "此质检记录不存在或不是 A3 接入记录。");
        return rows.get(0);
    }
    private JsonNode payload(Object raw) {
        try {
            if (raw instanceof byte[] bytes) return json.readTree(bytes);
            if (raw instanceof java.sql.Clob clob) return json.readTree(clob.getSubString(1, Math.toIntExact(clob.length())));
            return json.readTree(String.valueOf(raw));
        } catch (Exception e) { throw new InspectionFault(500, "SAVED_RECORD_INVALID", "已保存记录无法解析，请检查数据库，不要修改原始模型结果。"); }
    }
    public Map<String, Object> detail(long id) {
        checkSchema(); var r = row(id, false);
        JsonNode original = payload(r.get("response_json"));
        ObjectNode prediction = original.deepCopy(); prediction.remove("artifacts");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspection_id", id); result.put("batch_id", r.get("batch_id"));
        result.put("batch_code", original.path("batch_code").asText());
        result.put("prediction", prediction); result.put("persisted", true);
        result.put("artifact_urls", Map.of("input", "/api/inspections/" + id + "/artifacts/input", "result", "/api/inspections/" + id + "/artifacts/result"));
        var reviews = jdbc.query("SELECT * FROM inspection_reviews WHERE inspection_id=? ORDER BY revision", (rs, n) -> {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("revision", rs.getInt("revision")); review.put("reviewer", rs.getString("reviewer"));
            review.put("conclusion", rs.getString("conclusion")); review.put("remark", rs.getString("remark"));
            review.put("final_grade", rs.getString("final_grade")); review.put("publish_summary", rs.getBoolean("publish_summary"));
            review.put("candidate_reviews", payload(rs.getObject("candidate_reviews")));
            review.put("created_at", time(rs.getObject("created_at", LocalDateTime.class))); return review;
        }, id);
        result.put("review_revision", reviews.size()); result.put("reviews", reviews);
        result.put("latest_review", reviews.isEmpty() ? null : reviews.get(reviews.size() - 1));
        return result;
    }
    public Map<String, Object> review(long id, ReviewRules.Input input) {
        checkSchema();
        transaction.executeWithoutResult(status -> {
            var r = row(id, true);
            JsonNode p = payload(r.get("response_json"));
            ReviewRules.Input checked = ReviewRules.validate(input, p.path("detections").size());
            Integer revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM inspection_reviews WHERE inspection_id=?", Integer.class, id);
            if (!checked.expected_revision().equals(revision)) throw new InspectionFault(409, "REVIEW_VERSION_CONFLICT", "此记录已被复核或重复提交，请重新打开最新记录后再修改。");
            int next = Objects.requireNonNull(revision) + 1;
            jdbc.update("""
                INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,final_grade,candidate_reviews,publish_summary)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, next, checked.conclusion(), checked.reviewer(), checked.remark(), checked.final_grade(), encode(checked.candidate_reviews()), checked.publish_summary());
            jdbc.update("UPDATE inspections SET final_grade=?,reviewer=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?", checked.final_grade(), checked.reviewer(), id);
            jdbc.update("""
                INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,visibility)
                VALUES(?,'人工复核',CURRENT_TIMESTAMP,?,'manual',?,?)
                """, r.get("batch_id"), "已保存单张图像人工复核记录（第 " + next + " 版）；不构成食品安全或整批合格结论。",
                checked.reviewer(), checked.publish_summary() ? "public" : "private");
        });
        return detail(id);
    }
    public Path artifact(long id, String variant) {
        checkSchema(); var r = row(id, false);
        return store.file(UUID.fromString(String.valueOf(r.get("prediction_id"))), variant);
    }
    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> { var ps = c.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]); return ps; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON serialization failed", e); }
    }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String time(LocalDateTime value) { return value == null ? "" : value.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString(); }
}
