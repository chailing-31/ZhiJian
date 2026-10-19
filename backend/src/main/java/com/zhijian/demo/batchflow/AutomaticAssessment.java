package com.zhijian.demo.batchflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.util.*;
import static com.zhijian.demo.batchflow.BatchLedger.*;

/** Append-only application initial assessments. Caller holds the same batch row lock as uploads/reviews. */
public final class AutomaticAssessment {
    private AutomaticAssessment() {}
    private static long insert(JdbcTemplate db,String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public static void prepareRound(JdbcTemplate db,ObjectMapper json,long batch) {
        if(!ready(db)||activeRound(db,batch)!=null)return;
        long id=insert(db,"INSERT INTO q4_rounds(batch_id,name,sampling_note,created_by,request_id,request_hash) VALUES(?,?,?,?,?,?)",
            batch,"入厂质检","根据本轮上传的外观照片初评；后续可补充抽检范围或建立复检轮次。","AI",UUID.randomUUID().toString(),hash(json,Map.of("batch",batch,"action","initial_round")));
        for(Long iid:db.queryForList("SELECT i.id FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.batch_id=? ORDER BY i.id DESC LIMIT 500",Long.class,batch))
            db.update("INSERT INTO q4_round_images(round_id,inspection_id,sample_code) VALUES(?,?,NULL)",id,iid);
    }
    public static void synchronize(JdbcTemplate db,ObjectMapper json,long batch) {
        if(!ready(db))return;
        prepareRound(db,json,batch);
        var round=activeRound(db,batch);var e=evidence(db,json,batch,round);
        if(number(e.get("record_count"))==0||number(e.get("pending_count"))>0||Boolean.TRUE.equals(e.get("sample_conflict")))return;
        var latest=latestAssessment(db,json,batch);
        if(latest!=null&&number(latest.get("round_id"))==number(round.get("id"))
            &&!BatchPolicy.GRADES.contains(Objects.toString(latest.get("final_grade"),""))
            &&!"ai-batch-initial-v5".equals(latest.get("grade_policy_version")))return;
        if(current(latest,round,e))return; // A valid human correction remains authoritative.
        String grade="A";
        for(var record:json.valueToTree(e.get("records"))) {
            String value=record.path("effective_grade").asText();
            if(InitialGrade.rank(value)<0)throw fault(409,"INITIAL_GRADE_UNAVAILABLE","有图片缺少有效初评，请重新检测。");
            if(InitialGrade.rank(value)>InitialGrade.rank(grade))grade=value;
        }
        long rev=latest==null?1:number(latest.get("revision"))+1;
        String basis="依据本轮"+e.get("unique_photo_count")+"张照片的有效外观初评，采用最高问题等级"+grade+"；人工修正优先。";
        insert(db,"INSERT INTO q4_assessments(batch_id,round_id,revision,final_grade,intended_use,grade_basis,grade_policy_version,approved_by,evidence_fingerprint,evidence_json,request_id,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            batch,number(round.get("id")),rev,grade,InitialGrade.intendedUse(grade),basis,InitialGrade.BATCH_POLICY,"AI",e.get("fingerprint"),
            json.valueToTree(Map.of("round",round,"evidence",e)).toString(),UUID.randomUUID().toString(),hash(json,Map.of("policy",InitialGrade.BATCH_POLICY,"fingerprint",e.get("fingerprint"))));
        db.update("INSERT INTO batch_events(batch_id,event_type,event_time,summary,source,visibility) VALUES(?,'AI初评',CURRENT_TIMESTAMP,?,'ai-grading-policy','private')",
            batch,"本轮外观初评为"+grade+"，已成为后续品控等级来源，可后续人工修正。");
    }
}
