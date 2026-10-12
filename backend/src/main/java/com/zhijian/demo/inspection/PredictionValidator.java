package com.zhijian.demo.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.*;

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
            validateBurden(p.path("defect_burden"), detections, w, h);
            validateGrade(p, p.path("defect_burden"));
            String expected = detections.isEmpty() ? "no_target_defect_detected" : "target_defect_detected";
            if (!expected.equals(p.path("observation").asText()) || !p.path("requires_human_review").asBoolean()) fail();
            if (!p.path("warnings").isArray()) fail();
            return id;
        } catch (RuntimeException e) {
            throw new InspectionFault(502, "AI_CONTRACT_INVALID", "AI 响应与当前批次、图片或接口约定不符；未保存业务记录。");
        }
    }
    private static void validateGrade(JsonNode p, JsonNode burden) {
        if (!"a12-dev-grade-v1".equals(p.path("grade_rule_version").asText())
            || !"defect_burden_level".equals(p.path("grade_basis").asText())
            || !p.has("suggested_grade")) fail();

        String level = burden.path("burden_level").asText();
        if ("none_observed".equals(level)) {
            if (!p.get("suggested_grade").isNull()
                || !"withheld_no_target_observed".equals(p.path("grade_status").asText())) fail();
            return;
        }

        String expectedGrade = switch (level) {
            case "low" -> "B";
            case "moderate" -> "C";
            case "high" -> "D";
            default -> throw new IllegalArgumentException("Invalid burden level");
        };
        if (!p.get("suggested_grade").isTextual()
            || !expectedGrade.equals(p.get("suggested_grade").asText())
            || !"development_rule_applied".equals(p.path("grade_status").asText())) fail();
    }

    private static void validateBurden(JsonNode burden, JsonNode detections, int w, int h) {
        if (!burden.isObject() || !"a11-dev-burden-v1".equals(burden.path("rule_version").asText())
            || !"full_image_area".equals(burden.path("denominator").asText())) fail();

        JsonNode countNode = burden.path("detection_count");
        if (!countNode.isIntegralNumber() || countNode.asInt(-1) != detections.size()) fail();
        int count = countNode.asInt();

        range(burden.path("union_bbox_area_ratio_image"), 0, 1);
        range(burden.path("max_bbox_area_ratio_image"), 0, 1);
        double actualUnion = burden.path("union_bbox_area_ratio_image").asDouble();
        double actualMax = burden.path("max_bbox_area_ratio_image").asDouble();
        if (actualMax > actualUnion + 1e-6) fail();

        List<double[]> rects = new ArrayList<>();
        Map<Integer,Integer> counts = new HashMap<>();
        Map<Integer,String> names = new HashMap<>();
        Map<Integer,String> labels = new HashMap<>();
        Map<Integer,Double> classMax = new HashMap<>();
        boolean scratchLarge = false, pestLarge = false;
        double expectedMax = 0.0, imageArea = (double) w * h;

        for (JsonNode d : detections) {
            JsonNode b = d.path("bbox_xyxy");
            double x1=b.get(0).asDouble(), y1=b.get(1).asDouble(),
                   x2=b.get(2).asDouble(), y2=b.get(3).asDouble();
            rects.add(new double[]{x1,y1,x2,y2});
            double ratio = ((x2-x1)*(y2-y1))/imageArea;
            expectedMax = Math.max(expectedMax, ratio);
            int id = d.path("class_id").asInt();
            String name = d.path("class_name").asText(), label = d.path("class_label").asText();
            counts.merge(id, 1, Integer::sum);
            if (names.putIfAbsent(id, name) != null && !names.get(id).equals(name)) fail();
            if (labels.putIfAbsent(id, label) != null && !labels.get(id).equals(label)) fail();
            classMax.merge(id, ratio, Math::max);
            if ("ssda_class_0".equals(name) && ratio >= 0.025) scratchLarge = true;
            if ("ssda_class_1".equals(name) && ratio >= 0.0045) pestLarge = true;
        }

        double expectedUnion = unionRatio(rects, imageArea);
        if (!same(actualUnion, expectedUnion) || !same(actualMax, expectedMax)) fail();

        Set<String> expectedFlags = new HashSet<>();
        if (count >= 4) expectedFlags.add("detection_count_ge_4");
        if (scratchLarge) expectedFlags.add("scratch_large_box_ge_0_025");
        if (pestLarge) expectedFlags.add("pest_damage_large_box_ge_0_0045");

        JsonNode flags = burden.path("escalation_flags");
        if (!flags.isArray() || flags.size() > 3) fail();
        Set<String> actualFlags = new HashSet<>();
        Set<String> allowed = Set.of(
            "detection_count_ge_4",
            "scratch_large_box_ge_0_025",
            "pest_damage_large_box_ge_0_0045"
        );
        for (JsonNode flag : flags) {
            if (!flag.isTextual() || !allowed.contains(flag.asText())
                || !actualFlags.add(flag.asText())) fail();
        }
        if (!actualFlags.equals(expectedFlags)) fail();

        String expectedLevel;
        if (count == 0) expectedLevel = "none_observed";
        else {
            expectedLevel = expectedUnion < 0.006 ? "low"
                : expectedUnion < 0.03 ? "moderate" : "high";
            if (!expectedFlags.isEmpty()) {
                expectedLevel = "low".equals(expectedLevel) ? "moderate"
                    : "moderate".equals(expectedLevel) ? "high" : "high";
            }
        }
        if (!expectedLevel.equals(burden.path("burden_level").asText())) fail();

        JsonNode summary = burden.path("class_summary");
        if (!summary.isArray() || summary.size() != counts.size()) fail();
        Set<Integer> seen = new HashSet<>();
        int summaryTotal = 0;
        for (JsonNode item : summary) {
            JsonNode idNode = item.path("class_id"), cNode = item.path("count");
            if (!idNode.isIntegralNumber() || !cNode.isIntegralNumber()) fail();
            int id = idNode.asInt(-1), c = cNode.asInt(-1);
            if (!seen.add(id) || !counts.containsKey(id) || c != counts.get(id)) fail();
            bounded(item, "class_name", 100);
            bounded(item, "class_label", 100);
            if (!names.get(id).equals(item.path("class_name").asText())
                || !labels.get(id).equals(item.path("class_label").asText())) fail();
            range(item.path("max_bbox_area_ratio_image"), 0, 1);
            if (!same(item.path("max_bbox_area_ratio_image").asDouble(), classMax.get(id))) fail();
            summaryTotal += c;
        }
        if (summaryTotal != count) fail();
    }

    private static double unionRatio(List<double[]> rects, double imageArea) {
        if (rects.isEmpty()) return 0.0;
        List<Double> xs = new ArrayList<>();
        for (double[] r : rects) { xs.add(r[0]); xs.add(r[2]); }
        Collections.sort(xs);
        double total = 0.0;
        for (int i=0; i<xs.size()-1; i++) {
            double left=xs.get(i), right=xs.get(i+1);
            if (right <= left) continue;
            List<double[]> ys = new ArrayList<>();
            for (double[] r : rects) {
                if (r[0] < right && r[2] > left) ys.add(new double[]{r[1],r[3]});
            }
            if (ys.isEmpty()) continue;
            ys.sort(Comparator.comparingDouble(a -> a[0]));
            double start=ys.get(0)[0], end=ys.get(0)[1], covered=0.0;
            for (int j=1; j<ys.size(); j++) {
                double[] y=ys.get(j);
                if (y[0] <= end) end=Math.max(end,y[1]);
                else { covered += end-start; start=y[0]; end=y[1]; }
            }
            covered += end-start;
            total += (right-left)*covered;
        }
        return total/imageArea;
    }

    private static boolean same(double a, double b) {
        return Math.abs(a-b) <= 1e-6;
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
