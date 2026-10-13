package com.zhijian.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhijian.demo.business.BusinessIntegrationService;
import com.zhijian.demo.business.BusinessIntegrationFault;
import com.zhijian.demo.inspection.InspectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal/local engineering endpoints. Not an authentication or production grading service. */
@RestController
@Profile("inspection")
public class EngineeringWorkspaceController {
    public static final String PATCH_VERSION = "batch-workbench-v4-20261013";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InspectionService inspection;
    private final BusinessIntegrationService business;

    public EngineeringWorkspaceController(JdbcTemplate jdbc, ObjectMapper json,
            InspectionService inspection, BusinessIntegrationService business) {
        this.jdbc = jdbc; this.json = json; this.inspection = inspection; this.business = business;
    }

    @GetMapping("/workspace/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patch_version", PATCH_VERSION);
        result.put("scope", "trusted_local_development");
        result.put("database_ready", false);
        result.put("database_name", null);
        result.put("review_database", false);
        try {
            String name = jdbc.queryForObject("SELECT DATABASE()", String.class);
            jdbc.queryForList("SELECT id FROM batches LIMIT 0");
            result.put("database_ready", true);
            result.put("database_name", name);
            result.put("review_database", "smart_fresh_c_review".equals(name));
        } catch (RuntimeException error) {
            // Deliberately do not return SQL exceptions, paths or credentials.
            result.put("message", "数据库未连接，或当前数据库不支持该状态查询。");
        }
        result.put("batch_workbench_ready", com.zhijian.demo.batchflow.BatchLedger.ready(jdbc));
        result.put("write_flow_verified", false); // Health alone never asserts a write/read acceptance run.
        return result;
    }

    /** Explicitly approve a saved image-review grade as a business input; never trust a URL grade. */
    @PostMapping("/batches/{batchId}/reviewed-processing-advice")
    @Transactional
    public Map<String, Object> fromReview(@PathVariable long batchId, @RequestBody JsonNode input) {
        if(com.zhijian.demo.batchflow.BatchLedger.ready(jdbc) && com.zhijian.demo.batchflow.BatchLedger.activeRound(jdbc,batchId)!=null)
            throw new WorkspaceFault(409,"BATCH_ASSESSMENT_REQUIRED","该批次已建立抽检轮次，请通过批次评定生成工艺建议，不再引用单张图像等级。");
        long inspectionId = positiveInteger(input, "inspection_id");
        jdbc.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE", batchId);
        long revision = positiveInteger(input, "expected_review_revision");
        if (!input.path("use_as_material_grade_confirmed").isBoolean()
                || !input.path("use_as_material_grade_confirmed").asBoolean()) {
            throw new WorkspaceFault(400, "MATERIAL_SCOPE_NOT_CONFIRMED", "请明确确认：由人工批准将该图像复核等级用作本次原料业务输入。");
        }
        String operator = input.path("operator").asText("").trim();
        if (operator.isEmpty() || operator.length() > 50) {
            throw new WorkspaceFault(400, "INVALID_OPERATOR", "请填写 1—50 字的操作员名称。");
        }
        // Lock the same inspection row used by InspectionService.review(). Prevent stale-source races.
        List<Long> locked = jdbc.queryForList("SELECT id FROM inspections WHERE id=? AND batch_id=? FOR UPDATE",
                Long.class, inspectionId, batchId);
        if (locked.isEmpty()) throw new WorkspaceFault(404, "SOURCE_NOT_FOUND", "来源质检记录不存在或不属于当前批次。");
        JsonNode detail = json.valueToTree(inspection.detail(inspectionId));
        JsonNode review = detail.path("latest_review");
        if (detail.path("batch_id").asLong(-1) != batchId) {
            throw new WorkspaceFault(409, "BATCH_MISMATCH", "来源批次不匹配。");
        }
        if (review.isMissingNode() || review.isNull() || detail.path("review_revision").asLong() != revision
                || review.path("revision").asLong() != revision) {
            throw new WorkspaceFault(409, "REVIEW_VERSION_CONFLICT", "复核记录已更新或尚未完成，请重新读取来源。");
        }
        if (!Set.of("target_confirmed", "manual_target_confirmed", "no_target_confirmed").contains(review.path("conclusion").asText())) {
            throw new WorkspaceFault(409, "REVIEW_PENDING", "来源仍需复查，不能用于加工建议。");
        }
        String grade = review.path("final_grade").asText("").trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("A", "B", "C", "REJECT").contains(grade)) {
            throw new WorkspaceFault(409, "GRADE_NOT_SUPPORTED", "统一业务等级为 A/B/C/REJECT，历史 D 需要重新核定，不能静默映射。");
        }
        Long latest = jdbc.queryForObject("SELECT MAX(i.id) FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.batch_id=?", Long.class, batchId);
        if (latest == null || latest != inspectionId || ("A".equals(grade) && Set.of("target_confirmed","manual_target_confirmed").contains(review.path("conclusion").asText())))
            throw new WorkspaceFault(409,"LATEST_REVIEW_REQUIRED","请使用最新质检的有效复核，历史 A 与已确认目标缺陷冲突时须重新核定。");
        ObjectNode request = json.createObjectNode();
        request.put("grade", grade); request.put("operator", operator);
        for (String field : List.of("temperature", "humidity", "material_temperature")) {
            if (input.has(field)) request.set(field, input.get(field));
        }
        Map<String, Object> result = new LinkedHashMap<>(business.processingAdvice(batchId, request));
        ObjectNode storedInput = json.valueToTree(result.get("inputs"));
        ObjectNode source = storedInput.putObject("grade_source");
        source.put("type", "human_approved_inspection_review");
        source.put("quality_contract", QualityFlowRules.VERSION);
        source.put("inspection_id", inspectionId); source.put("review_revision", revision);
        source.put("reviewer", review.path("reviewer").asText()); source.put("final_grade", grade);
        source.put("approved_by", operator); source.put("material_scope_confirmed", true);
        source.put("scope", "explicit_business_input_not_automatic_batch_certification");
        long recordId = ((Number) result.get("record_id")).longValue();
        if (jdbc.update("UPDATE processing_records SET inputs_json=? WHERE id=? AND batch_id=?",
                storedInput.toString(), recordId, batchId) != 1) {
            throw new WorkspaceFault(500, "SOURCE_PERSIST_FAILED", "来源保存失败，事务已回滚。");
        }
        result.put("inputs", storedInput);
        result.put("grade_source", source);
        return result;
    }

    private static long positiveInteger(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new WorkspaceFault(400, "INVALID_SOURCE_ID", field + " 必须为正整数。");
        }
        return value.asLong();
    }
    @ExceptionHandler(WorkspaceFault.class)
    public ResponseEntity<Map<String, String>> handle(WorkspaceFault error) {
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage()));
    }
    @ExceptionHandler(BusinessIntegrationFault.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessIntegrationFault error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    static class WorkspaceFault extends RuntimeException {
        final int status; final String code;
        WorkspaceFault(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
