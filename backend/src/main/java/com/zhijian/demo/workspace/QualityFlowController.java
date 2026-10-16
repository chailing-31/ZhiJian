package com.zhijian.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhijian.demo.inspection.InspectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.*;

/** Joined, persisted evidence view. Health checks and row counts alone never certify a workflow. */
@RestController
@Profile("inspection")
public class QualityFlowController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InspectionService inspection;
    public QualityFlowController(JdbcTemplate jdbc, ObjectMapper json, InspectionService inspection) {
        this.jdbc=jdbc; this.json=json; this.inspection=inspection;
    }
    @GetMapping("/workspace/quality-contract")
    public Map<String,Object> contract() {
        return Map.of("version",com.zhijian.demo.batchflow.BatchPolicy.VERSION,
            "grades",List.of(Map.of("grade","A","label","优选外观"),Map.of("grade","B","label","一般外观，需分选"),Map.of("grade","C","label","用途待评估"),Map.of("grade","REJECT","label","拒收")),
            "initial_review_required",false,"grade_policy",com.zhijian.demo.batchflow.InitialGrade.IMAGE_POLICY,
            "detector_classes",List.of("表面擦伤","虫害损伤"),
            "scope","检测缺陷后按透明规则生成外观初评，人工可选修正；拒收初评与实际处置分别记录。",
            "workflow_endpoint","/batches/{id}/batch-workbench");
    }
    @GetMapping("/batches/{batchId}/quality-flow")
    @Transactional(readOnly=true)
    public Map<String,Object> state(@PathVariable long batchId) {
        var batch=jdbc.queryForList("SELECT id,batch_code FROM batches WHERE id=?",batchId);
        if(batch.isEmpty()) throw new FlowFault(404,"BATCH_NOT_FOUND","批次不存在。");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("version",QualityFlowRules.VERSION); out.put("batch_id",batchId);
        out.put("batch_code",batch.get(0).get("batch_code")); out.put("scope","image_review_to_explicit_material_input");
        Long latest=jdbc.queryForObject("SELECT MAX(i.id) FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.batch_id=?",Long.class,batchId);
        Map<String,Object> visual=new LinkedHashMap<>(); boolean reviewed=false; String grade=null; int revision=0;
        if(latest!=null) {
            JsonNode d=json.valueToTree(inspection.detail(latest)); JsonNode r=d.path("latest_review");
            revision=d.path("review_revision").asInt();
            grade=r.path("final_grade").isTextual()?r.path("final_grade").asText():null;
            reviewed=Set.of("target_confirmed","manual_target_confirmed","no_target_confirmed").contains(r.path("conclusion").asText())
                && QualityFlowRules.GRADES.contains(grade==null?"":grade)
                && !("A".equals(grade) && Set.of("target_confirmed","manual_target_confirmed").contains(r.path("conclusion").asText()));
            visual.put("inspection_id",latest); visual.put("review_revision",revision); visual.put("final_grade",grade);
            visual.put("reviewed",reviewed); visual.put("review_conclusion",r.path("conclusion").asText()); visual.put("reviewer",r.path("reviewer").asText(null));
            visual.put("candidate_count",d.path("prediction").path("detections").size());
            visual.put("model_version",d.path("prediction").path("model_version").asText());
            visual.put("grade_rule_version",d.path("prediction").path("grade_rule_version").asText());
            visual.put("runtime",d.path("prediction").path("runtime"));
        }
        out.put("inspection",visual);
        // Select the newest saved suggestion. Never silently fall back to an older adopted one.
        var ps=jdbc.queryForList("SELECT id,inputs_json,advice_json,adopted_values_json,operator,created_at FROM processing_records WHERE batch_id=? ORDER BY id DESC LIMIT 1",batchId);
        Map<String,Object> processing=new LinkedHashMap<>(); boolean linked=false,adopted=false; String adviceStatus="";
        if(!ps.isEmpty()) {
            var p=ps.get(0); JsonNode inputs=parse(p.get("inputs_json")), advice=parse(p.get("advice_json")), values=parse(p.get("adopted_values_json"));
            JsonNode source=inputs.path("grade_source");
            linked=reviewed && latest!=null && source.path("inspection_id").asLong(-1)==latest
                && source.path("review_revision").asInt(-1)==revision
                && "human_approved_inspection_review".equals(source.path("type").asText())
                && source.path("material_scope_confirmed").asBoolean(false)
                && Objects.equals(grade,inputs.path("grade").asText(null))
                && Objects.equals(grade,source.path("final_grade").asText(null));
            adviceStatus=advice.path("status").asText();
            adopted=values.isObject() && !values.path("pre_cooling_time").asText("").isBlank()
                && !values.path("washing_pressure").asText("").isBlank() && "suggested".equals(adviceStatus);
            processing.put("record_id",p.get("id")); processing.put("linked_to_current_review",linked);
            processing.put("source",source); processing.put("advice_status",adviceStatus);
            processing.put("adopted",adopted); processing.put("inputs",inputs); processing.put("advice",advice);
            processing.put("adopted_values",values);
        }
        out.put("processing",processing);
        long readings=count("SELECT COUNT(*) FROM sensor_readings WHERE batch_id=?",batchId);
        long sim=count("SELECT COUNT(*) FROM sensor_readings WHERE batch_id=? AND source='simulation'",batchId);
        long sensor=count("SELECT COUNT(*) FROM sensor_readings WHERE batch_id=? AND source='sensor'",batchId);
        long unresolved=count("SELECT COUNT(*) FROM alerts WHERE batch_id=? AND (status IS NULL OR status<>'resolved')",batchId);
        long ongoing=count("SELECT COUNT(*) FROM coldchain_episode_records WHERE batch_id=? AND end_reason='ongoing'",batchId);
        long gaps=count("SELECT COUNT(*) FROM coldchain_episode_records WHERE batch_id=? AND end_reason='data_gap'",batchId);
        long latestReading=count("SELECT COALESCE(MAX(id),0) FROM sensor_readings WHERE batch_id=?",batchId);
        var cold=new LinkedHashMap<String,Object>();
        cold.put("reading_count",readings); cold.put("simulation_count",sim); cold.put("sensor_count",sensor);
        cold.put("unresolved_alerts",unresolved); cold.put("ongoing_episodes",ongoing); cold.put("gap_episodes",gaps);
        cold.put("latest_reading_id",latestReading); cold.put("link_type","same_batch_not_grade_generated_sensor_values");
        out.put("coldchain",cold);
        String state=QualityFlowRules.nextState(reviewed,grade,linked,adviceStatus,adopted,readings,unresolved,ongoing);
        out.put("state",state); out.put("records_linked","RECORDS_LINKED".equals(state));
        out.put("accuracy_verified",false); out.put("scope_statement","记录按同一批次关联；冷链数据不由照片生成，采用值不代表设备已执行，RECORDS_LINKED不是质量认证。");
        // Hash also includes non-public event changes (adoption / alert disposition) but not publication itself.
        long event=count("SELECT COALESCE(MAX(id),0) FROM batch_events WHERE batch_id=? AND source<>'quality-flow-v3'",batchId);
        out.put("source_event_id",event);
        String fingerprint=hash(write(canonical(json.valueToTree(out)))); out.put("fingerprint",fingerprint);
        var pub=jdbc.queryForList("SELECT event_time,summary,evidence_path FROM batch_events WHERE batch_id=? AND source='quality-flow-v3' AND visibility='public' ORDER BY id DESC LIMIT 1",batchId);
        if(!pub.isEmpty()) {
            var p=pub.get(0); out.put("publication",Map.of("summary",p.get("summary"),"published_at",time(p.get("event_time")),
                "current",String.valueOf(p.get("evidence_path")).startsWith("/quality-flow/"+fingerprint+"/")));
        }
        return out;
    }
    @PostMapping("/batches/{batchId}/quality-flow/publish")
    @Transactional
    public Map<String,Object> publish(@PathVariable long batchId,@RequestBody JsonNode input) {
        if(input == null || !input.isObject()) throw new FlowFault(400,"INVALID_INPUT","请求必须为 JSON 对象。");
        if(!input.path("confirm_public_summary").isBoolean() || !input.path("confirm_public_summary").asBoolean())
            throw new FlowFault(400,"CONFIRM_REQUIRED","请明确批准发布白名单摘要。");
        String operator=input.path("operator").asText("").trim();
        if(operator.isEmpty()||operator.length()>50) throw new FlowFault(400,"OPERATOR_REQUIRED","请填写发布操作员。");
        jdbc.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE",batchId);
        var current=state(batchId);
        if(!Objects.equals(input.path("expected_fingerprint").asText(),current.get("fingerprint")))
            throw new FlowFault(409,"SOURCE_UPDATED","记录已更新，请刷新并重新确认发布内容。");
        boolean showGrade=input.path("publish_grade").asBoolean(false);
        JsonNode v=json.valueToTree(current.get("inspection")),p=json.valueToTree(current.get("processing")),c=json.valueToTree(current.get("coldchain"));
        if(!v.path("reviewed").asBoolean()) throw new FlowFault(409,"REVIEW_REQUIRED","最新图像尚未完成有效业务等级复核，不发布品控摘要。");
        StringBuilder summary=new StringBuilder("已保存抽检图像的人工复核记录");
        if(showGrade) summary.append("，人工业务等级 ").append(v.path("final_grade").asText());
        summary.append("；");
        if(p.path("linked_to_current_review").asBoolean()) summary.append(p.path("adopted").asBoolean()?"已保存与该复核关联的工艺采用值":"已保存关联工艺建议，尚未完成采用");
        else summary.append("尚无与最新复核关联的工艺记录");
        summary.append("；同批次冷链记录 ").append(c.path("reading_count").asLong()).append(" 条（模拟 ").append(c.path("simulation_count").asLong()).append(" 条、传感器来源 ").append(c.path("sensor_count").asLong()).append(" 条）");
        summary.append("；未处置告警 ").append(c.path("unresolved_alerts").asLong()).append(" 条，持续异常区段 ").append(c.path("ongoing_episodes").asLong()).append(" 段。仅为截至发布时间的记录摘要，不是整批合格或食品安全结论。");
        String key="/quality-flow/"+current.get("fingerprint")+"/"+(showGrade?"grade":"private-grade");
        var existing=jdbc.queryForList("SELECT id FROM batch_events WHERE batch_id=? AND source='quality-flow-v3' AND evidence_path=?",batchId,key);
        if(existing.isEmpty()) jdbc.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,evidence_path,visibility) VALUES(?,'品控流程摘要',CURRENT_TIMESTAMP,?,'quality-flow-v3',?,?,'public')",batchId,summary.toString(),operator,key);
        return Map.of("persisted",true,"duplicate",!existing.isEmpty(),"summary",summary.toString(),"fingerprint",current.get("fingerprint"));
    }
    @GetMapping("/trace/{batchCode}/quality-flow")
    @Transactional(readOnly=true)
    public Map<String,Object> publicSummary(@PathVariable String batchCode) {
        var ids=jdbc.queryForList("SELECT id FROM batches WHERE batch_code=?",Long.class,batchCode);
        if(ids.isEmpty()) throw new FlowFault(404,"BATCH_NOT_FOUND","批次不存在。");
        var rows=jdbc.queryForList("SELECT event_time,summary,evidence_path FROM batch_events WHERE batch_id=? AND source='quality-flow-v3' AND visibility='public' ORDER BY id DESC LIMIT 1",ids.get(0));
        if(rows.isEmpty()) return Map.of("published",false);
        var p=rows.get(0);
        // Only the explicitly approved string and timestamp leave this endpoint, never private model or operator data.
        String key="/quality-flow/"+state(ids.get(0)).get("fingerprint")+"/";
        return Map.of("published",true,"summary",p.get("summary"),"published_at",time(p.get("event_time")),"current",String.valueOf(p.get("evidence_path")).startsWith(key));
    }
    private long count(String sql,long id) {Long n=jdbc.queryForObject(sql,Long.class,id);return n==null?0:n;}
    private JsonNode parse(Object v) {
        if(v==null)return json.getNodeFactory().nullNode();
        try {JsonNode n=v instanceof byte[] b?json.readTree(b):v instanceof java.sql.Clob c?json.readTree(c.getSubString(1, Math.toIntExact(c.length()))):json.readTree(v.toString());return n.isTextual()?json.readTree(n.asText()):n;}
        catch(Exception e){throw new FlowFault(500,"SAVED_RECORD_INVALID","已保存记录无法解析，请勿据此判断流程完成。");}
    }
    private JsonNode canonical(JsonNode n) {
        if(n.isObject()){var out=json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)out.set(k,canonical(n.get(k)));return out;}
        if(n.isArray()){var out=json.createArrayNode();for(JsonNode v:n)out.add(canonical(v));return out;}
        return n;
    }
    private String write(Object v) {try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String time(Object v){return v instanceof Timestamp t?t.toLocalDateTime().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString():String.valueOf(v);}
    @ExceptionHandler(FlowFault.class)
    public ResponseEntity<Map<String,String>> error(FlowFault e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    static final class FlowFault extends RuntimeException{final int status;final String code;FlowFault(int s,String c,String m){super(m);status=s;code=c;}}
}
