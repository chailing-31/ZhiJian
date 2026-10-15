package com.zhijian.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhijian.demo.business.BusinessIntegrationFault;
import org.springframework.jdbc.core.JdbcTemplate;

/** Recheck source at adoption time, not only when a suggestion was first created. */
public final class QualitySourceGuard {
    private QualitySourceGuard() {}
    public static void verify(JdbcTemplate jdbc, ObjectMapper json, long batchId, JsonNode inputs) {
        boolean q4Source = inputs != null && "approved_batch_assessment".equals(inputs.path("grade_source").path("type").asText());
        if (q4Source || (com.zhijian.demo.batchflow.BatchLedger.ready(jdbc)
                && com.zhijian.demo.batchflow.BatchLedger.activeRound(jdbc,batchId)!=null)) {
            // Use the same date/time serializers as assessment creation. MySQL returns
            // LocalDateTime for DATETIME; a bare mapper both fails and changes source hashes.
            com.zhijian.demo.batchflow.BatchLedger.verifyAdoption(jdbc,json,batchId,inputs);
            return;
        }
        JsonNode source = inputs == null ? null : inputs.get("grade_source");
        // Legacy standalone inputs remain legacy; the V3 flow never labels them as linked.
        if (source == null || !"human_approved_inspection_review".equals(source.path("type").asText())) return;
        long id = source.path("inspection_id").asLong(-1);
        int revision = source.path("review_revision").asInt(-1);
        jdbc.queryForList("SELECT id FROM inspections WHERE id=? AND batch_id=? FOR UPDATE", id, batchId);
        Long latest = jdbc.queryForObject("SELECT MAX(i.id) FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.batch_id=?", Long.class, batchId);
        var rows = jdbc.queryForList("SELECT revision,final_grade,conclusion FROM inspection_reviews WHERE inspection_id=? ORDER BY revision DESC LIMIT 1", id);
        if (latest == null || latest != id || rows.isEmpty()) stale();
        var review = rows.get(0);
        String grade = String.valueOf(review.get("final_grade"));
        if (((Number)review.get("revision")).intValue()!=revision
            || !grade.equals(inputs.path("grade").asText()) || !grade.equals(source.path("final_grade").asText())
            || !source.path("material_scope_confirmed").asBoolean(false)
            || ("A".equals(grade) && java.util.Set.of("target_confirmed","manual_target_confirmed").contains(String.valueOf(review.get("conclusion"))))
            || !QualityFlowRules.GRADES.contains(grade)
            || !java.util.Set.of("target_confirmed","manual_target_confirmed","no_target_confirmed").contains(String.valueOf(review.get("conclusion")))) stale();
        Long unresolved = jdbc.queryForObject("SELECT COUNT(*) FROM alerts WHERE batch_id=? AND (status IS NULL OR status<>'resolved')",Long.class,batchId);
        Long ongoing = jdbc.queryForObject("SELECT COUNT(*) FROM coldchain_episode_records WHERE batch_id=? AND end_reason='ongoing'",Long.class,batchId);
        if ((unresolved != null && unresolved>0) || (ongoing != null && ongoing>0))
            throw new BusinessIntegrationFault(409,"COLDCHAIN_HOLD","当前有未处置告警或仍在持续的异常区段，暂停采用新工艺；人工处置不等于观测恢复。");
    }
    private static void stale() {
        throw new BusinessIntegrationFault(409,"STALE_QUALITY_SOURCE","质检或复核已更新，旧工艺建议不能继续采用。请根据最新复核重新生成建议。");
    }
}
