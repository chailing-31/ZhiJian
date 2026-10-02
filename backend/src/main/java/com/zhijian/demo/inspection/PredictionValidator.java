package com.zhijian.demo.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

final class PredictionValidator {
    private PredictionValidator() {}
    static UUID validate(JsonNode p, long batchId, String code, String uploadHash) {
        try {
            if (!p.isObject() || !"zhijian.ai.inspection.v0.1".equals(p.path("schema_version").asText())
                || p.path("batch_id").asLong(-1) != batchId || !code.equals(p.path("batch_code").asText())) fail();
            UUID id = UUID.fromString(p.path("prediction_id").asText());
            if (!id.toString().equals(p.path("prediction_id").asText())) fail();
            bounded(p, "model_version", 50);
            sha(p.path("weights_sha256").asText());
            bounded(p, "evaluation_status", 40);
            OffsetDateTime.parse(p.path("executed_at").asText());
            range(p.path("inference_ms"), 0, 3600000);
            range(p.path("confidence_threshold"), 0, 1); range(p.path("iou_threshold"), 0, 1);
            JsonNode image = p.path("image"), detections = p.path("detections");
            int w = image.path("width").asInt(0), h = image.path("height").asInt(0);
            if (w < 1 || h < 1 || (long) w * h > 24000000 || !uploadHash.equals(image.path("source_sha256").asText())
                || !"exif_transposed_pixels_xyxy".equals(image.path("coordinate_system").asText())) fail();
            sha(image.path("normalized_pixels_sha256").asText());
            if (!detections.isArray() || detections.size() > 300) fail();
            for (JsonNode d : detections) {
                if (!d.path("class_id").isIntegralNumber() || d.path("class_id").asInt(-1) < 0) fail();
                bounded(d, "class_name", 100); bounded(d, "class_label", 200);
                range(d.path("confidence"), 0, 1);
                JsonNode b = d.path("bbox_xyxy");
                if (!b.isArray() || b.size() != 4) fail();
                range(b.get(0), 0, w); range(b.get(1), 0, h); range(b.get(2), 0, w); range(b.get(3), 0, h);
                if (b.get(0).asDouble() >= b.get(2).asDouble() || b.get(1).asDouble() >= b.get(3).asDouble()) fail();
            }
            String expected = detections.isEmpty() ? "no_target_defect_detected" : "target_defect_detected";
            if (!expected.equals(p.path("observation").asText()) || !p.path("requires_human_review").asBoolean()
                || !p.has("suggested_grade") || !p.get("suggested_grade").isNull()
                || !"grading_rule_not_configured".equals(p.path("grade_status").asText())) fail();
            if (!p.path("warnings").isArray()) fail();
            return id;
        } catch (RuntimeException e) {
            throw new InspectionFault(502, "AI_CONTRACT_INVALID", "AI 响应与当前批次、图片或接口约定不符；未保存业务记录。");
        }
    }
    private static void sha(String s) { if (!s.matches("[0-9a-f]{64}")) fail(); }
    private static void bounded(JsonNode p, String key, int max) {
        JsonNode value = p.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) fail();
    }
    private static void range(JsonNode v, double low, double high) {
        if (v == null || !v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < low || v.asDouble() > high) fail();
    }
    private static void fail() { throw new IllegalArgumentException("Invalid prediction"); }
}
