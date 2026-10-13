package com.zhijian.demo.batchflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhijian.demo.business.BusinessIntegrationService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.zhijian.demo.batchflow.BatchLedger.*;

@Service
@Profile("inspection")
public class BatchWorkbenchService {
    private final JdbcTemplate db; private final ObjectMapper json; private final BusinessIntegrationService business;
    public BatchWorkbenchService(JdbcTemplate db,ObjectMapper json,BusinessIntegrationService business){this.db=db;this.json=json;this.business=business;}
    private void lock(long batch){if(db.queryForList("SELECT id FROM batches WHERE id=? FOR UPDATE",Long.class,batch).isEmpty())throw fault(404,"BATCH_NOT_FOUND","批次不存在。");requireSchema(db);}
    private long insert(String sql,Object... args){var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private void event(long batch,String type,String summary,String operator){db.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,operator,visibility) VALUES(?,?,CURRENT_TIMESTAMP,?,'batch-workbench-v4',?,'private')",batch,type,summary,operator);}
    private static String text(JsonNode input,String key,int max){JsonNode n=input==null?null:input.get(key);if(n==null||!n.isTextual()||n.asText().trim().isEmpty()||n.asText().trim().length()>max)throw fault(400,"INVALID_INPUT",key+" 为空、类型不符或过长。");return n.asText().trim();}
    private static long positive(JsonNode input,String key){JsonNode n=input==null?null:input.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<1)throw fault(400,"INVALID_ID",key+" 必须是正整数。");return n.asLong();}
    private static void consent(JsonNode input,String key){if(input==null||!input.path(key).isBoolean()||!input.path(key).asBoolean())throw fault(400,"CONFIRM_REQUIRED","请明确确认 "+key+"。");}
    private static String requestId(JsonNode input){String s=text(input,"request_id",36);try{String v=UUID.fromString(s).toString();if(!s.equals(v))throw new IllegalArgumentException();return v;}catch(Exception e){throw fault(400,"INVALID_REQUEST_ID","request_id 必须是规范 UUID。");}}
    private Long repeated(String table,String idCol,long batch,JsonNode input){String key=requestId(input);var rows=db.queryForList("SELECT "+idCol+",batch_id,request_hash FROM "+table+" WHERE request_id=?",key);if(rows.isEmpty())return null;var r=rows.get(0);if(number(r.get("batch_id"))!=batch||!Objects.equals(r.get("request_hash"),hash(json,input)))throw fault(409,"REQUEST_ID_REUSED","同一请求编号不能用于不同输入。");return number(r.get(idCol));}
    private Map<String,Object> saved(long id,String kind,long batch){var out=new LinkedHashMap<String,Object>();out.put("persisted",true);out.put("id",id);out.put("kind",kind);out.put("workbench",view(batch));return out;}

    @Transactional
    public Map<String,Object> createRound(long batch,JsonNode input){
        lock(batch);Long old=repeated("q4_rounds","id",batch,input);if(old!=null)return saved(old,"round",batch);
        JsonNode expected=input.path("expected_round_id");var currentRound=activeRound(db,batch);
        if(!expected.isIntegralNumber()||!expected.canConvertToLong()||expected.asLong()<0||expected.asLong()!=number(currentRound==null?null:currentRound.get("id")))throw fault(409,"ROUND_UPDATED","抽检轮次已更新，请刷新。");
        String name=text(input,"name",80),note=text(input,"sampling_note",2000),operator=text(input,"operator",50);
        consent(input,"new_round_confirmed");
        long id=insert("INSERT INTO q4_rounds(batch_id,name,sampling_note,created_by,request_id,request_hash) VALUES(?,?,?,?,?,?)",batch,name,note,operator,requestId(input),hash(json,input));
        event(batch,"抽检轮次","已建立抽检轮次 #"+id+"；旧评定和工艺保留为历史。",operator);return saved(id,"round",batch);
    }
    @Transactional
    public Map<String,Object> bindImage(long batch,long roundId,JsonNode input){
        lock(batch);var round=activeRound(db,batch);if(round==null||number(round.get("id"))!=roundId)throw fault(409,"ROUND_UPDATED","当前抽检轮次已改变，请刷新。");
        String expected=text(input,"expected_fingerprint",64);var before=evidence(db,json,batch,round);
        if(!Objects.equals(expected,before.get("fingerprint")))throw fault(409,"EVIDENCE_UPDATED","图片或复核已更新，请刷新后再设置样品编号。");
        long iid=positive(input,"inspection_id");String sample=null;
        if(input.hasNonNull("sample_code")){if(!input.path("sample_code").isTextual())throw fault(400,"INVALID_SAMPLE","样品编号应为文本。");sample=input.path("sample_code").asText().trim();if(sample.isEmpty())sample=null;else if(!sample.matches("[A-Za-z0-9_-]{1,64}"))throw fault(400,"INVALID_SAMPLE","样品编号使用1—64位字母、数字、下划线或连字符。");}
        if(db.queryForList("SELECT i.id FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.id=? AND i.batch_id=?",Long.class,iid,batch).isEmpty())throw fault(404,"IMAGE_NOT_FOUND","该图片记录不属于当前批次或缺少原始预测。");
        long n=count(db,"SELECT COUNT(*) FROM q4_round_images WHERE round_id=? AND inspection_id=?",roundId,iid);
        if(n==0){if(number(before.get("record_count"))>=MAX_IMAGES)throw fault(409,"ROUND_FULL","本轮最多500条记录。");db.update("INSERT INTO q4_round_images(round_id,inspection_id,sample_code) VALUES(?,?,?)",roundId,iid,sample);}
        else db.update("UPDATE q4_round_images SET sample_code=? WHERE round_id=? AND inspection_id=?",sample,roundId,iid);
        var after=evidence(db,json,batch,round);if(Boolean.TRUE.equals(after.get("sample_conflict")))throw fault(409,"SAMPLE_CONFLICT","同一像素图片不能标成不同果实；请统一样品编号。");
        return Map.of("persisted",true,"inspection_id",iid,"workbench",view(batch));
    }
    @Transactional
    public Map<String,Object> assess(long batch,JsonNode input){
        lock(batch);Long old=repeated("q4_assessments","id",batch,input);if(old!=null)return saved(old,"assessment",batch);
        long rid=positive(input,"round_id");var round=activeRound(db,batch);var e=evidence(db,json,batch,round);var latest=latestAssessment(db,json,batch);
        if(round==null||number(round.get("id"))!=rid||!Objects.equals(text(input,"expected_fingerprint",64),e.get("fingerprint")))throw fault(409,"EVIDENCE_UPDATED","本轮证据已变化，请刷新后重新批准。");
        JsonNode expected=input.path("expected_assessment_id");if(!expected.isIntegralNumber()||!expected.canConvertToLong()||expected.asLong()<0||expected.asLong()!=number(latest==null?null:latest.get("id")))throw fault(409,"ASSESSMENT_UPDATED","批次评定版本已更新，请刷新。");
        String grade=text(input,"final_grade",16),use=text(input,"intended_use",40),basis=text(input,"grade_basis",3000),operator=text(input,"operator",50);
        consent(input,"scope_confirmed");consent(input,"use_confirmed");if(basis.length()<10)throw fault(400,"BASIS_REQUIRED","请至少填写10字的批次评定依据、适用范围和用途限制。");
        try{BatchPolicy.validateAssessment(grade,use,(int)number(e.get("record_count")),(int)number(e.get("pending_count")),(int)number(e.get("confirmed_target_records")));}catch(IllegalArgumentException ex){throw fault(400,"ASSESSMENT_INVALID",ex.getMessage());}
        if(Boolean.TRUE.equals(e.get("sample_conflict")))throw fault(409,"SAMPLE_CONFLICT","样品编号冲突，请先修正。");
        long rev=latest==null?1:number(latest.get("revision"))+1;
        long id=insert("INSERT INTO q4_assessments(batch_id,round_id,revision,final_grade,intended_use,grade_basis,grade_policy_version,approved_by,evidence_fingerprint,evidence_json,request_id,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch,rid,rev,grade,use,basis,BatchPolicy.GRADE_POLICY,operator,e.get("fingerprint"),json.valueToTree(Map.of("round",round,"evidence",e)).toString(),requestId(input),hash(json,input));
        event(batch,"批次评定","已保存第"+rev+"版人工业务评定；依据本轮抽检，不是自动整批认证。",operator);return saved(id,"assessment",batch);
    }
    @Transactional
    public Map<String,Object> generateAdvice(long batch,JsonNode input){
        lock(batch);Long old=repeated("q4_processing_links","processing_id",batch,input);if(old!=null)return saved(old,"processing",batch);
        long aid=positive(input,"assessment_id"),revision=positive(input,"assessment_revision");var a=requireCurrent(db,json,batch,aid,revision);
        String operator=text(input,"operator",50);consent(input,"use_as_process_input_confirmed");
        ObjectNode request=json.createObjectNode();request.put("grade",Objects.toString(a.get("final_grade")));request.put("operator",operator);
        for(String field:List.of("temperature","humidity","material_temperature"))if(input.has(field))request.set(field,input.get(field));
        // B receives only its existing strict API fields through the C adapter. Source metadata stays in Java/MySQL.
        var result=business.processingAdvice(batch,request);long pid=number(result.get("record_id"));ObjectNode stored=json.valueToTree(result.get("inputs"));
        var src=stored.putObject("grade_source");src.put("type","approved_batch_assessment");src.put("assessment_id",aid);src.put("revision",revision);src.put("round_id",number(a.get("round_id")));
        src.put("final_grade",Objects.toString(a.get("final_grade")));src.put("intended_use",Objects.toString(a.get("intended_use")));src.put("evidence_fingerprint",Objects.toString(a.get("evidence_fingerprint")));src.put("approved_by",operator);src.put("approved_for_process_input",true);
        if(db.update("UPDATE processing_records SET inputs_json=? WHERE id=? AND batch_id=?",stored.toString(),pid,batch)!=1)throw fault(500,"SOURCE_SAVE_FAILED","来源保存失败，本次事务回滚。");
        db.update("INSERT INTO q4_processing_links(processing_id,batch_id,assessment_id,request_id,request_hash) VALUES(?,?,?,?,?)",pid,batch,aid,requestId(input),hash(json,input));
        return saved(pid,"processing",batch);
    }
    @Transactional
    public Map<String,Object> operation(long batch,JsonNode input){
        lock(batch);Long old=repeated("q4_operations","id",batch,input);if(old!=null)return saved(old,"operation",batch);
        long aid=positive(input,"assessment_id"),revision=positive(input,"assessment_revision");var a=requireCurrent(db,json,batch,aid,revision);
        String kind=text(input,"kind",32),operator=text(input,"operator",50),note=text(input,"note",2000);consent(input,"actually_performed_confirmed");
        OffsetDateTime at;try{at=OffsetDateTime.parse(text(input,"performed_at",40));}catch(Exception ex){throw fault(400,"INVALID_TIME","执行时间必须含时区，例如 2026-10-13T15:00:00+08:00。");}
        if(at.isAfter(OffsetDateTime.now(ZONE).plusMinutes(5))||at.getYear()<2000||at.getNano()!=0)throw fault(400,"INVALID_TIME","执行时间须精确到秒，不能是未来时间。");
        String grade=Objects.toString(a.get("final_grade"));Long pid=null;String pf=null;
        switch(kind){
            case "sorting_completed" -> {if(!Set.of("A","B").contains(grade)||!Set.of("premium_sorting","general_sorting").contains(a.get("intended_use")))throw fault(409,"USE_REVIEW_REQUIRED","当前等级或用途需先专项评估，不能登记正常商品分选完成。");}
            case "processing_completed" -> {
                pid=positive(input,"processing_id");var p=latestProcessing(batch,a);
                if(number(p.get("record_id"))!=pid||!Boolean.TRUE.equals(p.get("linked"))||!Boolean.TRUE.equals(p.get("adopted")))throw fault(409,"PROCESS_NOT_ADOPTED","请先按当前评定保存工艺采用值。");
                verifyAdoption(db,json,batch,json.valueToTree(p.get("inputs")));pf=Objects.toString(p.get("fingerprint"));
                if(!Objects.equals(pf,text(input,"expected_processing_fingerprint",64)))throw fault(409,"PROCESS_UPDATED","采用参数已更新，请刷新后确认实际执行内容。");
            }
            case "reject_disposition" -> {if(!"REJECT".equals(grade))throw fault(409,"NOT_REJECTED","当前批次不是人工拒收。");}
            default -> throw fault(400,"INVALID_OPERATION","仅接受实际分选、加工执行或拒收处置记录。");
        }
        long id=insert("INSERT INTO q4_operations(batch_id,assessment_id,processing_id,kind,performed_at,operator,note,processing_fingerprint,request_id,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",batch,aid,pid,kind,Timestamp.valueOf(at.atZoneSameInstant(ZONE).toLocalDateTime()),operator,note,pf,requestId(input),hash(json,input));
        event(batch,"作业执行记录","已由人员登记 "+kind+"；不是设备自动反馈。",operator);return saved(id,"operation",batch);
    }
    private Map<String,Object> latestProcessing(long batch,Map<String,Object> a){
        var out=new LinkedHashMap<String,Object>();out.put("linked",false);out.put("adopted",false);
        var rows=db.queryForList("SELECT id,inputs_json,advice_json,adopted_values_json,operator,created_at FROM processing_records WHERE batch_id=? ORDER BY id DESC LIMIT 1",batch);
        if(rows.isEmpty())return out;var p=rows.get(0);JsonNode inputs=parse(json,p.get("inputs_json")),advice=parse(json,p.get("advice_json")),adopted=parse(json,p.get("adopted_values_json"));
        JsonNode src=inputs.path("grade_source");boolean linked=a!=null&&"approved_batch_assessment".equals(src.path("type").asText())&&src.path("assessment_id").asLong(-1)==number(a.get("id"))&&src.path("revision").asLong(-1)==number(a.get("revision"))
            &&Objects.equals(a.get("evidence_fingerprint"),src.path("evidence_fingerprint").asText())&&Objects.equals(a.get("final_grade"),inputs.path("grade").asText())&&Objects.equals(a.get("intended_use"),src.path("intended_use").asText())
            &&count(db,"SELECT COUNT(*) FROM q4_processing_links WHERE processing_id=? AND batch_id=? AND assessment_id=?",p.get("id"),batch,a.get("id"))==1;
        out.put("record_id",p.get("id"));out.put("inputs",inputs);out.put("advice",advice);out.put("adopted_values",adopted);out.put("created_at",time(p.get("created_at")));out.put("operator",p.get("operator"));
        out.put("linked",linked);out.put("advice_status",advice.path("status").asText());out.put("adopted",adopted.isObject()&&!adopted.path("pre_cooling_time").asText("").isBlank()&&!adopted.path("washing_pressure").asText("").isBlank()&&"suggested".equals(advice.path("status").asText()));
        out.put("fingerprint",processingFingerprint(db,json,number(p.get("id"))));return out;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> view(long batch){
        requireSchema(db);var batches=db.queryForList("SELECT id,batch_code,product,variety,origin,supplier,created_at FROM batches WHERE id=?",batch);
        if(batches.isEmpty())throw fault(404,"BATCH_NOT_FOUND","批次不存在。");
        var round=activeRound(db,batch);var e=evidence(db,json,batch,round);var a=latestAssessment(db,json,batch);boolean valid=current(a,round,e);
        var p=latestProcessing(batch,a);boolean linked=valid&&Boolean.TRUE.equals(p.get("linked"));p.put("current",linked);
        var cold=cold(db,json,batch);var ops=new ArrayList<Map<String,Object>>();boolean sorted=false,executed=false;
        for(var raw:db.queryForList("SELECT id,assessment_id,processing_id,kind,performed_at,operator,note,processing_fingerprint,created_at FROM q4_operations WHERE batch_id=? ORDER BY id DESC",batch)){
            var o=clean(raw);boolean matches=valid&&a!=null&&number(o.get("assessment_id"))==number(a.get("id"));
            if("sorting_completed".equals(o.get("kind"))&&matches)sorted=true;
            boolean processCurrent=matches&&linked&&number(o.get("processing_id"))==number(p.get("record_id"))&&Objects.equals(o.get("processing_fingerprint"),p.get("fingerprint"));
            if("processing_completed".equals(o.get("kind"))&&processCurrent)executed=true;
            o.put("current",matches&&(!"processing_completed".equals(o.get("kind"))||processCurrent));ops.add(o);
        }
        String grade=a==null?null:Objects.toString(a.get("final_grade"));String use=a==null?null:Objects.toString(a.get("intended_use"));
        var f=new BatchPolicy.Facts(round!=null,(int)number(e.get("record_count")),(int)number(e.get("pending_count")),a!=null,valid,grade,use,sorted,linked,Objects.toString(p.get("advice_status")),Boolean.TRUE.equals(p.get("adopted")),executed,number(cold.get("reading_count")),Boolean.TRUE.equals(cold.get("hold")));
        String state=BatchPolicy.next(f);var out=new LinkedHashMap<String,Object>();out.put("version",BatchPolicy.VERSION);out.put("batch",clean(batches.get(0)));out.put("round",round);out.put("evidence",e);out.put("assessment",a);out.put("assessment_current",valid);out.put("processing",p);out.put("coldchain",cold);out.put("operations",ops);
        out.put("sorting_recorded",sorted);out.put("processing_executed",executed);out.put("state",state);out.put("next_action",Map.of("label",BatchPolicy.actionLabel(state),"tab",BatchPolicy.tab(state)));
        String headline=a==null?"尚未形成批次业务评定":(valid?"人工业务评定：":"历史评定待更新：")+grade+" · "+BatchPolicy.gradeLabel(grade);
        out.put("headline",headline);out.put("use_label",BatchPolicy.useLabel(use));out.put("grade_label",BatchPolicy.gradeLabel(grade));
        out.put("evidence_summary","本轮保存 "+e.get("record_count")+" 条图片记录（去重照片 "+e.get("unique_photo_count")+" 张），已复核 "+e.get("reviewed_count")+" 条，确认缺陷 "+e.get("confirmed_target_records")+" 条；待复核 "+e.get("pending_count")+" 条。");
        out.put("scope_note","等级和用途由人员依据本轮抽检确认；候选不等于缺陷，图片数不等于果实数。工艺为 B 演示规则，执行结果须另行登记。");
        out.put("release_certified",false); // No automatic release/certification workflow is implemented in this phase.
        var adoptionRows=new ArrayList<Map<String,Object>>();
        for(var raw:db.queryForList("SELECT id,processing_id,values_json,operator,note,created_at FROM q4_adoptions WHERE batch_id=? ORDER BY id DESC",batch)) {var row=clean(raw);row.put("values",parse(json,row.remove("values_json")));adoptionRows.add(row);}
        out.put("adoption_history",adoptionRows);
        var assessmentHistory=new ArrayList<Map<String,Object>>();
        for(var row:db.queryForList("SELECT id,round_id,revision,final_grade,intended_use,grade_basis,grade_policy_version,approved_by,created_at FROM q4_assessments WHERE batch_id=? ORDER BY revision DESC LIMIT 50",batch)) assessmentHistory.add(clean(row));
        out.put("assessment_history",assessmentHistory);
        out.put("assessment_history_truncated",count(db,"SELECT COUNT(*) FROM q4_assessments WHERE batch_id=?",batch)>assessmentHistory.size());
        out.put("fingerprint",hash(json,out));
        var pub=db.queryForList("SELECT id,source_fingerprint,snapshot_json,published_at FROM q4_publications WHERE batch_id=? ORDER BY id DESC LIMIT 1",batch);
        if(!pub.isEmpty()){var r=pub.get(0);out.put("publication",Map.of("id",r.get("id"),"snapshot",parse(json,r.get("snapshot_json")),"published_at",time(r.get("published_at")),"current",Objects.equals(r.get("source_fingerprint"),out.get("fingerprint"))));}
        return out;
    }
    private Map<String,Object> publicSnapshot(Map<String,Object> w,JsonNode input){
        JsonNode a=json.valueToTree(w.get("assessment")),p=json.valueToTree(w.get("processing")),c=json.valueToTree(w.get("coldchain")),b=json.valueToTree(w.get("batch")),e=json.valueToTree(w.get("evidence"));
        if(!Boolean.TRUE.equals(w.get("assessment_current")))throw fault(409,"ASSESSMENT_REQUIRED","请先完成当前批次评定，不能公开过期结论。");
        var s=new LinkedHashMap<String,Object>();s.put("batch_code",b.path("batch_code").asText());s.put("assessment_revision",a.path("revision").asLong());
        s.put("evidence_scope","本轮抽检图片 "+e.path("unique_photo_count").asInt()+" 张；摘要不代表整批全检。");
        s.put("quality_summary","本轮抽检与人工业务评定已有保存记录。");
        if(input.path("publish_grade").asBoolean(false))s.put("grade",a.path("final_grade").asText()+" · "+BatchPolicy.gradeLabel(a.path("final_grade").asText()));
        if(input.path("publish_use").asBoolean(false))s.put("intended_use",BatchPolicy.useLabel(a.path("intended_use").asText()));
        s.put("processing_summary",!p.path("current").asBoolean()?"尚无与当前评定关联的工艺建议":Boolean.TRUE.equals(w.get("processing_executed"))?"已登记人工加工执行记录":p.path("adopted").asBoolean()?"已保存工艺采用值，尚无当前执行记录":"已保存工艺建议，尚未采用");
        var cs=new ArrayList<Map<String,Object>>();for(JsonNode row:c.path("sources"))cs.add(Map.of("source",row.path("source").asText(),"reading_count",row.path("reading_count").asLong()));
        s.put("coldchain_sources",cs);s.put("coldchain_summary",c.path("reading_count").asLong()==0?"尚无冷链记录":c.path("hold").asBoolean()?"存在待核查观测或告警":"已有同批次观测记录，末点状态不代表当前实测保证");
        s.put("scope_note","经批准公开的业务记录快照；不包含原图和内部备注，不是食品安全或整批合格证明。");return s;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> preview(long batch,JsonNode input){var w=view(batch);return Map.of("snapshot",publicSnapshot(w,input),"source_fingerprint",w.get("fingerprint"));}
    @Transactional
    public Map<String,Object> publish(long batch,JsonNode input){
        lock(batch);Long old=repeated("q4_publications","id",batch,input);if(old!=null)return saved(old,"publication",batch);
        consent(input,"confirm_public_summary");String operator=text(input,"operator",50);var w=view(batch);
        if(!Objects.equals(text(input,"expected_fingerprint",64),w.get("fingerprint")))throw fault(409,"PREVIEW_EXPIRED","预览后记录有变化，请重新预览并批准。");
        var snapshot=publicSnapshot(w,input);long aid=number(((Map<?,?>)w.get("assessment")).get("id"));
        long id=insert("INSERT INTO q4_publications(batch_id,assessment_id,source_fingerprint,snapshot_json,approved_by,request_id,request_hash) VALUES(?,?,?,?,?,?,?)",batch,aid,w.get("fingerprint"),json.valueToTree(snapshot).toString(),operator,requestId(input),hash(json,input));
        // Not inserted into old public events: this structured whitelist snapshot is the sole Q4 publication source.
        return saved(id,"publication",batch);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> publicView(String code){
        requireSchema(db);var ids=db.queryForList("SELECT id FROM batches WHERE batch_code=?",Long.class,code);if(ids.isEmpty())throw fault(404,"BATCH_NOT_FOUND","批次不存在。");long batch=ids.get(0);
        var rows=db.queryForList("SELECT source_fingerprint,snapshot_json,published_at FROM q4_publications WHERE batch_id=? ORDER BY id DESC LIMIT 1",batch);
        if(rows.isEmpty())return Map.of("published",false);
        var r=rows.get(0);return Map.of("published",true,"snapshot",parse(json,r.get("snapshot_json")),"published_at",time(r.get("published_at")),"current",Objects.equals(r.get("source_fingerprint"),view(batch).get("fingerprint")));
    }
}
