package com.zhijian.demo.batchflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Explainable provisional grade from real detector evidence; manual corrections have priority. */
public final class InitialGrade {
    public static final String IMAGE_POLICY = "visual-initial-v5.1";
    public static final String BATCH_POLICY = "ai-batch-initial-v5.1";
    private InitialGrade() {}
    public static String grade(JsonNode prediction) {
        return switch (prediction.path("defect_burden").path("burden_level").asText()) {
            case "none_observed" -> "A"; case "low" -> "B";
            case "moderate" -> "C"; case "high" -> "REJECT";
            default -> throw BatchLedger.fault(502,"INITIAL_GRADE_UNAVAILABLE","检测证据不完整，请重新检测，未生成初评等级。");
        };
    }
    public static int rank(String grade) {
        return switch (grade) { case "A" -> 0; case "B" -> 1; case "C" -> 2; case "REJECT" -> 3; default -> -1; };
    }
    public static String intendedUse(String grade) {
        return switch (grade) { case "A" -> "premium_sorting"; case "B" -> "general_sorting";
            case "C" -> "processing_evaluation"; default -> "isolate_return"; };
    }
    public static Map<String,Object> assessment(JsonNode prediction) {
        String grade = grade(prediction);
        return Map.of("grade",grade,"source","ai_initial","policy_version",IMAGE_POLICY,
            "scope","visible_target_defects_in_submitted_image","review_required",false,
            "basis", "A".equals(grade) ? "当前模型未检出所覆盖的擦伤或虫害目标" : "根据已检出的目标缺陷负担生成外观初评",
            "coverage_note","当前模型覆盖擦伤和虫害；初评可由人工修正，不等同于完整质量标准检测。");
    }
}
