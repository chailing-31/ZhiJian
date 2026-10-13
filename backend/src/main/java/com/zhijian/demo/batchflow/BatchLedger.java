package com.zhijian.demo.batchflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.zhijian.demo.business.BusinessIntegrationFault;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Shared snapshot and source validation. Call mutation helpers while holding the batch row lock. */
public final class BatchLedger {
    private BatchLedger() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MAX_IMAGES = 500;
    public static boolean ready(JdbcTemplate db) {
        try {
            db.queryForList("SELECT id,batch_id,name,sampling_note,created_by,request_id,request_hash,created_at FROM q4_rounds LIMIT 0");
            db.queryForList("SELECT round_id,inspection_id,sample_code FROM q4_round_images LIMIT 0");
            db.queryForList("SELECT id,batch_id,round_id,revision,final_grade,intended_use,grade_basis,grade_policy_version,approved_by,evidence_fingerprint,evidence_json,request_id,request_hash,created_at FROM q4_assessments LIMIT 0");
            db.queryForList("SELECT processing_id,batch_id,assessment_id,request_id,request_hash FROM q4_processing_links LIMIT 0");
            db.queryForList("SELECT id,batch_id,assessment_id,processing_id,kind,performed_at,operator,note,processing_fingerprint,request_id,request_hash FROM q4_operations LIMIT 0");
            db.queryForList("SELECT batch_id,source,reading_count,analysis_json FROM q4_coldchain_results LIMIT 0");
            db.queryForList("SELECT id,batch_id,assessment_id,source_fingerprint,snapshot_json,approved_by,request_id,request_hash,published_at FROM q4_publications LIMIT 0");
            db.queryForList("SELECT id,batch_id,processing_id,values_json,operator,note,created_at FROM q4_adoptions LIMIT 0");
            return true;
        } catch (org.springframework.dao.DataAccessException e) { return false; }
    }
    public static void requireSchema(JdbcTemplate db) {
        if (!ready(db)) throw fault(503,"Q4_SCHEMA_REQUIRED","批次工作台附加表尚未就绪。请运行补丁的增量迁移；不要重新初始化原数据库。");
    }
    public static BusinessIntegrationFault fault(int status,String code,String message) {
        return new BusinessIntegrationFault(status,code,message);
    }
    public static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    public static long count(JdbcTemplate db,String sql,Object... args) { Long n=db.queryForObject(sql,Long.class,args);return n==null?0:n; }
    public static String time(Object value) { return value instanceof Timestamp t?t.toLocalDateTime().atZone(ZONE).toOffsetDateTime().toString():String.valueOf(value); }
    public static Map<String,Object> clean(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();row.forEach((k,v)->out.put(k,v instanceof Timestamp?time(v):v));return out;
    }
    public static JsonNode parse(ObjectMapper json,Object raw) {
        if(raw==null)return json.getNodeFactory().nullNode();
        try {
            JsonNode n=raw instanceof byte[] b?json.readTree(b):raw instanceof Clob c?json.readTree(c.getSubString(1,Math.toIntExact(c.length()))):json.readTree(raw.toString());
            return n.isTextual()?json.readTree(n.asText()):n;
        } catch(Exception e) {throw fault(500,"SAVED_JSON_INVALID","已保存记录无法解析，请检查数据；不能据此认定正常或已完成。");}
    }
    public static JsonNode canonical(ObjectMapper json,JsonNode n) {
        if(n.isObject()) { ObjectNode out=json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)out.set(k,canonical(json,n.get(k)));return out; }
        if(n.isArray()) {ArrayNode out=json.createArrayNode();for(JsonNode v:n)out.add(canonical(json,v));return out;}
        return n;
    }
    public static String hash(ObjectMapper json,Object value) {
        String s=canonical(json,json.valueToTree(value)).toString();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Map<String,Object> activeRound(JdbcTemplate db,long batch) {
        var rows=db.queryForList("SELECT * FROM q4_rounds WHERE batch_id=? ORDER BY id DESC LIMIT 1",batch);
        if(rows.isEmpty())return null;
        var r=clean(rows.get(0));r.remove("request_id");r.remove("request_hash");return r;
    }
    public static void enrollUpload(JdbcTemplate db,long batch,long inspection) {
        if(!ready(db))return; // The pre-Q4 image endpoint remains usable before the additive migration.
        var r=activeRound(db,batch);if(r==null)return;
        long round=number(r.get("id"));
        if(count(db,"SELECT COUNT(*) FROM q4_round_images WHERE round_id=?",round)>=MAX_IMAGES)
            throw fault(409,"ROUND_FULL","本轮达到500条记录上限，请另建抽检轮次再上传。");
        if(count(db,"SELECT COUNT(*) FROM q4_round_images WHERE round_id=? AND inspection_id=?",round,inspection)==0)
            db.update("INSERT INTO q4_round_images(round_id,inspection_id,sample_code) VALUES(?,?,NULL)",round,inspection);
    }
    public static Map<String,Object> evidence(JdbcTemplate db,ObjectMapper json,long batch,Map<String,Object> round) {
        var result=new LinkedHashMap<String,Object>();var list=new ArrayList<Map<String,Object>>();
        var hashes=new HashSet<String>();var samples=new HashSet<String>();var imageSamples=new HashMap<String,String>();
        int pending=0,confirmed=0,manual=0,candidates=0;boolean allSamples=true, sampleConflict=false;
        if(round!=null) {
            long roundId=number(round.get("id"));
            var rows=db.queryForList("""
                SELECT i.id,p.response_json,l.sample_code,r.revision,r.conclusion,r.reviewer,r.remark,r.final_grade,r.candidate_reviews
                FROM q4_round_images l JOIN inspections i ON i.id=l.inspection_id
                JOIN inspection_payloads p ON p.inspection_id=i.id
                LEFT JOIN inspection_reviews r ON r.inspection_id=i.id AND r.revision=(SELECT MAX(r2.revision) FROM inspection_reviews r2 WHERE r2.inspection_id=i.id)
                WHERE l.round_id=? AND i.batch_id=? ORDER BY i.id
                """,roundId,batch);
            long linked=count(db,"SELECT COUNT(*) FROM q4_round_images WHERE round_id=?",roundId);
            if(linked!=rows.size())throw fault(409,"EVIDENCE_MISSING","本轮关联包含缺失或跨批次的证据，请检查，不会跳过后生成结论。");
            for(var row:rows) {
                long id=number(row.get("id"));JsonNode prediction=parse(json,row.get("response_json"));
                String source=prediction.path("image").path("normalized_pixels_sha256").asText("");
                if(source.isBlank())source=prediction.path("image").path("source_sha256").asText("");
                if(source.isBlank())throw fault(409,"IMAGE_HASH_MISSING","历史图片缺少身份哈希，不能用于本轮证据去重。");
                hashes.add(source);String sample=(String)row.get("sample_code");
                if(sample==null||sample.isBlank())allSamples=false;
                else {samples.add(sample);String previous=imageSamples.putIfAbsent(source,sample);if(previous!=null&&!previous.equals(sample))sampleConflict=true;}
                String conclusion=Objects.toString(row.get("conclusion"),"");boolean done=BatchPolicy.reviewed(conclusion);
                if(!done)pending++;
                int candidateCount=prediction.path("detections").size();candidates+=candidateCount;
                var confirmedClasses=new LinkedHashSet<String>();
                JsonNode reviewCandidates=parse(json,row.get("candidate_reviews"));
                for(JsonNode c:reviewCandidates) if("confirmed".equals(c.path("decision").asText())) {
                    int index=c.path("candidate_index").asInt(0)-1;
                    if(index>=0&&index<candidateCount)confirmedClasses.add(prediction.path("detections").get(index).path("class_label").asText("已确认目标"));
                }
                boolean defect=Set.of("target_confirmed","manual_target_confirmed").contains(conclusion)||!confirmedClasses.isEmpty();
                if(defect)confirmed++;if("manual_target_confirmed".equals(conclusion))manual++;
                var item=new LinkedHashMap<String,Object>();item.put("inspection_id",id);item.put("sample_code",sample);
                item.put("image_hash",source);item.put("model_version",prediction.path("model_version").asText());
                item.put("prediction_hash",hash(json,prediction));item.put("review_revision",number(row.get("revision")));
                item.put("reviewed",done);item.put("conclusion",conclusion);item.put("candidate_count",candidateCount);
                item.put("confirmed_target",defect);item.put("confirmed_classes",confirmedClasses);item.put("image_grade",row.get("final_grade"));
                var review=new LinkedHashMap<String,Object>();for(String k:List.of("revision","conclusion","reviewer","remark","final_grade"))review.put(k,row.get(k));review.put("candidate_reviews",reviewCandidates);
                item.put("review_hash",hash(json,review));item.put("review_remark",row.get("remark"));
                item.put("input_url","/api/inspections/"+id+"/artifacts/input");item.put("result_url","/api/inspections/"+id+"/artifacts/result");
                list.add(item);
            }
        }
        result.put("records",list);result.put("record_count",list.size());result.put("unique_photo_count",hashes.size());
        result.put("pending_count",pending);result.put("reviewed_count",list.size()-pending);result.put("confirmed_target_records",confirmed);
        result.put("manual_target_records",manual);result.put("candidate_count",candidates);
        result.put("all_samples_identified",!list.isEmpty()&&allSamples&&!sampleConflict);
        result.put("identified_sample_count",!list.isEmpty()&&allSamples&&!sampleConflict?samples.size():null);
        result.put("sample_conflict",sampleConflict);result.put("fruit_defect_rate",null);
        result.put("scope_note","按本轮照片和人工样品编号汇总，不从图片条数推算整批果实合格率。");
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("batch_id",batch);snapshot.put("round",round);snapshot.put("evidence",result);
        result.put("fingerprint",hash(json,snapshot));return result;
    }
    public static Map<String,Object> latestAssessment(JdbcTemplate db,ObjectMapper json,long batch) {
        var rows=db.queryForList("SELECT * FROM q4_assessments WHERE batch_id=? ORDER BY revision DESC LIMIT 1",batch);
        if(rows.isEmpty())return null;var out=clean(rows.get(0));out.put("evidence",parse(json,out.remove("evidence_json")));
        out.remove("request_id");out.remove("request_hash");return out;
    }
    public static boolean current(Map<String,Object> assessment,Map<String,Object> round,Map<String,Object> evidence) {
        return assessment!=null&&round!=null&&number(assessment.get("round_id"))==number(round.get("id"))
            &&Objects.equals(assessment.get("evidence_fingerprint"),evidence.get("fingerprint"));
    }
    public static Map<String,Object> requireCurrent(JdbcTemplate db,ObjectMapper json,long batch,long assessmentId,long revision) {
        requireSchema(db);var r=activeRound(db,batch);var e=evidence(db,json,batch,r);var a=latestAssessment(db,json,batch);
        if(a==null||number(a.get("id"))!=assessmentId||number(a.get("revision"))!=revision||!current(a,r,e))
            throw fault(409,"ASSESSMENT_STALE","抽检或评定已更新。请重新核定批次并重新生成工艺建议，不再使用旧来源。");
        return a;
    }
    public static void verifyAdoption(JdbcTemplate db,ObjectMapper json,long batch,JsonNode inputs) {
        requireSchema(db);JsonNode source=inputs.path("grade_source");
        if(!"approved_batch_assessment".equals(source.path("type").asText()))throw fault(409,"BATCH_ASSESSMENT_REQUIRED","该批次已启用批次评定，请通过批次工作台生成工艺建议。");
        long aid=source.path("assessment_id").asLong(-1);var a=requireCurrent(db,json,batch,aid,source.path("revision").asLong(-1));
        if(!Objects.equals(a.get("final_grade"),inputs.path("grade").asText())||!Objects.equals(a.get("evidence_fingerprint"),source.path("evidence_fingerprint").asText())
            ||!Objects.equals(a.get("intended_use"),source.path("intended_use").asText())||!source.path("approved_for_process_input").asBoolean(false))
            throw fault(409,"GRADE_SOURCE_MISMATCH","工艺等级、用途或批准来源不匹配。");
        if(!Set.of("A","B").contains(Objects.toString(a.get("final_grade")))||Set.of("processing_evaluation","undetermined","isolate_return").contains(Objects.toString(a.get("intended_use"))))
            throw fault(409,"USE_REVIEW_REQUIRED","当前等级或用途仍需专项评估，不能通过手填参数绕过。");
        if(count(db,"SELECT COUNT(*) FROM q4_operations WHERE batch_id=? AND assessment_id=? AND kind='sorting_completed'",batch,aid)==0)
            throw fault(409,"SORTING_RECORD_REQUIRED","请先登记本次实际分选结果；仅有工艺建议不等于已分选。");
        var cold=cold(db,json,batch);
        if(Boolean.TRUE.equals(cold.get("hold")))throw fault(409,"COLDCHAIN_REVIEW_REQUIRED","已有冷链异常、未处置告警或未核实的观测状态，请先核查。");
    }
    public static String processingFingerprint(JdbcTemplate db,ObjectMapper json,long pid) {
        var rows=db.queryForList("SELECT id,batch_id,inputs_json,advice_json,adopted_values_json,operator,created_at FROM processing_records WHERE id=?",pid);
        if(rows.isEmpty())throw fault(404,"PROCESSING_NOT_FOUND","工艺记录不存在。");
        var r=clean(rows.get(0));
        for(String k:List.of("inputs_json","advice_json","adopted_values_json"))r.put(k,parse(json,r.get(k)));
        r.put("adoption_revision",count(db,"SELECT COALESCE(MAX(id),0) FROM q4_adoptions WHERE processing_id=?",pid));
        return hash(json,r);
    }
    public static void checkAdoptionRequest(JdbcTemplate db,ObjectMapper json,long batch,long pid,JsonNode input) {
        if(!ready(db))return;
        if(activeRound(db,batch)==null)return;
        long latest=count(db,"SELECT COALESCE(MAX(id),0) FROM processing_records WHERE batch_id=?",batch);
        if(pid!=latest)throw fault(409,"NEWER_ADVICE_EXISTS","已有更新的工艺建议，不再采用旧建议。");
        if(!Objects.equals(processingFingerprint(db,json,pid),input.path("expected_processing_fingerprint").asText()))
            throw fault(409,"ADOPTION_UPDATED","工艺参数或采用记录已更新，请刷新并核对，不覆盖他人的采用记录。");
        JsonNode values=input.path("adopted_values");
        for(String key:List.of("pre_cooling_time","washing_pressure","action")) {
            JsonNode value=values.get(key);if(value!=null&&(!value.isTextual()||value.asText().length()>500))throw fault(400,"INVALID_ADOPTION","采用参数须为不超过500字的文本。");
        }
        if(!input.path("note").isTextual()||input.path("note").asText().isBlank()||input.path("note").asText().length()>2000)
            throw fault(400,"ADOPTION_NOTE_REQUIRED","请填写1—2000字的采用或修改依据。");
    }
    public static void recordAdoption(JdbcTemplate db,long batch,long pid,String values,String operator,String note) {
        if(!ready(db)||count(db,"SELECT COUNT(*) FROM q4_processing_links WHERE processing_id=? AND batch_id=?",pid,batch)==0)return;
        db.update("INSERT INTO q4_adoptions(batch_id,processing_id,values_json,operator,note) VALUES(?,?,?,?,?)",batch,pid,values,operator,note==null?"":note);
    }
    public static void saveCold(JdbcTemplate db,long batch,String source,int size,JsonNode analysis) {
        if(!ready(db))return;
        if(count(db,"SELECT COUNT(*) FROM q4_coldchain_results WHERE batch_id=? AND source=?",batch,source)==0)
            db.update("INSERT INTO q4_coldchain_results(batch_id,source,reading_count,analysis_json) VALUES(?,?,?,?)",batch,source,size,analysis.toString());
        else db.update("UPDATE q4_coldchain_results SET reading_count=?,analysis_json=?,updated_at=CURRENT_TIMESTAMP WHERE batch_id=? AND source=?",size,analysis.toString(),batch,source);
    }
    public static Map<String,Object> cold(JdbcTemplate db,ObjectMapper json,long batch) {
        var out=new LinkedHashMap<String,Object>();var sources=new ArrayList<Map<String,Object>>();
        long total=0;boolean unknown=false,observedHold=false;
        for(String source:List.of("sensor","simulation")) {
            long n=count(db,"SELECT COUNT(*) FROM sensor_readings WHERE batch_id=? AND source=?",batch,source);total+=n;
            var s=new LinkedHashMap<String,Object>();s.put("source",source);s.put("reading_count",n);
            var latest=db.queryForList("SELECT id,timestamp,temperature,humidity FROM sensor_readings WHERE batch_id=? AND source=? ORDER BY timestamp DESC,id DESC LIMIT 1",batch,source);
            s.put("latest",latest.isEmpty()?null:clean(latest.get(0)));
            var a=db.queryForList("SELECT reading_count,analysis_json FROM q4_coldchain_results WHERE batch_id=? AND source=?",batch,source);
            JsonNode analysis=a.isEmpty()?json.getNodeFactory().nullNode():parse(json,a.get(0).get("analysis_json"));
            boolean known=n>0&&!a.isEmpty()&&number(a.get(0).get("reading_count"))==n;
            String state=n==0?"not_started":known?analysis.path("status").asText("unverified"):"unverified";
            s.put("status",state);s.put("analysis",known?analysis:null);
            s.put("freshness","sensor".equals(source)&&n>0?"requires_operator_check":"not_live_sensor_data");
            // No invented freshness timeout: timestamps and data gaps are shown; no live-safety claim is made.
            if(n>0&&!known)unknown=true;
            if(known&&!"normal".equals(state))observedHold=true;
            if(known&&analysis.path("data_gap_count").asLong()>0)s.put("has_historical_gap",true);
            sources.add(s);
        }
        long unresolved=count(db,"SELECT COUNT(*) FROM alerts WHERE batch_id=? AND (status IS NULL OR status<>'resolved')",batch);
        long ongoing=count(db,"SELECT COUNT(*) FROM coldchain_episode_records WHERE batch_id=? AND end_reason='ongoing'",batch);
        out.put("reading_count",total);out.put("sources",sources);out.put("unresolved_alerts",unresolved);out.put("ongoing_episodes",ongoing);
        out.put("hold",unresolved>0||ongoing>0||unknown||observedHold);out.put("unverified_history",unknown);
        out.put("status",total==0?"not_started":Boolean.TRUE.equals(out.get("hold"))?"review_required":"observations_recorded");
        out.put("last_alert_event",count(db,"SELECT COALESCE(MAX(id),0) FROM batch_events WHERE batch_id=? AND event_type IN ('冷链告警','冷链处置')",batch));
        out.put("scope_note","同批次、每种来源限同一监测对象；保留 B 原始观测状态。末点正常不代表当前实测正常或食品安全。");
        return out;
    }
}
