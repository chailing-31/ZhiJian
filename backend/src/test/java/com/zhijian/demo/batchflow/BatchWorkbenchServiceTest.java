package com.zhijian.demo.batchflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhijian.demo.business.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 + mocked B protocol tests. No localhost MySQL, no real model, no sensor claims. */
class BatchWorkbenchServiceTest {
    JdbcTemplate db; ObjectMapper json=new ObjectMapper(); BatchWorkbenchService service; BusinessIntegrationService business;TransactionTemplate tx;
    ObjectNode node(String... entries){ObjectNode n=json.createObjectNode();for(int i=0;i<entries.length;i+=2)n.put(entries[i],entries[i+1]);n.put("request_id",UUID.randomUUID().toString());return n;}
    <T>T transaction(Supplier<T> run){return tx.execute(s->run.get());}
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:q4"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));business=mock(BusinessIntegrationService.class);service=new BatchWorkbenchService(db,json,business);
        db.execute("CREATE TABLE batches(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_code VARCHAR(64),product VARCHAR(80),variety VARCHAR(80),origin VARCHAR(80),supplier VARCHAR(80),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE inspections(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT)");
        db.execute("CREATE TABLE inspection_payloads(inspection_id BIGINT PRIMARY KEY,response_json CLOB)");
        db.execute("CREATE TABLE inspection_reviews(id BIGINT AUTO_INCREMENT PRIMARY KEY,inspection_id BIGINT,revision INT,conclusion VARCHAR(40),reviewer VARCHAR(50),remark CLOB,final_grade VARCHAR(20),candidate_reviews CLOB)");
        db.execute("CREATE TABLE processing_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,inputs_json CLOB,advice_json CLOB,adopted_values_json CLOB,operator VARCHAR(50),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE batch_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,event_type VARCHAR(80),event_time TIMESTAMP,summary CLOB,source VARCHAR(80),operator VARCHAR(50),visibility VARCHAR(10))");
        db.execute("CREATE TABLE sensor_readings(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,source VARCHAR(16),timestamp TIMESTAMP,temperature FLOAT,humidity FLOAT)");
        db.execute("CREATE TABLE alerts(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,status VARCHAR(40))");
        db.execute("CREATE TABLE coldchain_episode_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,end_reason VARCHAR(40))");
        String sql=Files.readString(Path.of("../database/migrations/20261013_Q4_batch_workbench.sql"));
        // H2 fixture adapts MySQL storage options; production DDL is executed separately against MySQL.
        sql=sql.replaceAll("(?m)^--.*$","").replaceAll("(?m)^ INDEX .*\\n","").replaceAll("(?m)^ UNIQUE KEY (\\w+)\\(([^)]*)\\),"," CONSTRAINT $1 UNIQUE($2),").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bJSON\\b","CLOB");
        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
        db.update("INSERT INTO batches(id,batch_code,product) VALUES(1,'Q4-TEST-A','测试苹果'),(2,'Q4-TEST-B','测试苹果')");
        when(business.processingAdvice(anyLong(),any())).thenAnswer(inv->{
            long b=inv.getArgument(0);JsonNode in=inv.getArgument(1);String grade=in.path("grade").asText();
            String status=grade.equals("C")?"manual_review":grade.equals("REJECT")?"blocked":"suggested";
            var params=new LinkedHashMap<String,Object>();params.put("pre_cooling_time",status.equals("suggested")?"6h":null);params.put("washing_pressure",status.equals("suggested")?"normal":null);params.put("action","TEST FIXTURE ONLY");
            Map<String,Object> advice=Map.of("status",status,"advice",params,"rule_version","mock-B-not-live","demo_only",true);
            ObjectNode stored=in.deepCopy();stored.put("batch_id",b);stored.remove("operator");
            db.update("INSERT INTO processing_records(batch_id,inputs_json,advice_json,operator) VALUES(?,?,?,?)",b,stored.toString(),json.valueToTree(advice).toString(),in.path("operator").asText());
            long id=BatchLedger.count(db,"SELECT MAX(id) FROM processing_records");return Map.of("record_id",id,"inputs",stored,"advice",advice,"persisted",true);
        });
    }
    ObjectNode roundInput(){var n=node("name","TEST ROUND","sampling_note","TEST FIXTURE: selected images, not actual sampling","operator","Q4 TEST");n.put("expected_round_id",0);n.put("new_round_confirmed",true);return n;}
    long start(){var r=transaction(()->service.createRound(1,roundInput()));return BatchLedger.number(r.get("id"));}
    void image(long id,String hash,boolean reviewed){
        db.update("INSERT INTO inspections(id,batch_id) VALUES(?,1)",id);ObjectNode p=json.createObjectNode();p.putObject("image").put("normalized_pixels_sha256",hash);p.put("model_version","fixture");p.putArray("detections");p.putObject("defect_burden").put("burden_level","none_observed");
        db.update("INSERT INTO inspection_payloads(inspection_id,response_json) VALUES(?,?)",id,p.toString());
        transaction(()->{BatchLedger.enrollUpload(db,1,id);return null;});
        if(reviewed)db.update("INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,candidate_reviews) VALUES(?,1,'no_target_confirmed','Q4 TEST','MOCK REVIEW ONLY','[]')",id);
    }
    Map<String,Object> assess(String grade,String use){var w=service.view(1);var r=json.valueToTree(w.get("round"));var a=json.valueToTree(w.get("assessment"));var n=node("final_grade",grade,"intended_use",use,"grade_basis","TEST FIXTURE ONLY: not an actual batch quality judgment","operator","Q4 TEST");n.put("round_id",r.path("id").asLong());n.put("expected_assessment_id",a.path("id").asLong());n.put("expected_fingerprint",json.valueToTree(w.get("evidence")).path("fingerprint").asText());n.put("scope_confirmed",true);n.put("use_confirmed",true);return transaction(()->service.assess(1,n));}
    ObjectNode adviceInput(){var a=json.valueToTree(service.view(1).get("assessment"));var n=node("operator","Q4 TEST");n.put("assessment_id",a.path("id").asLong());n.put("assessment_revision",a.path("revision").asLong());n.put("use_as_process_input_confirmed",true);n.put("temperature",5);n.put("humidity",80);return n;}
    @Test void traceRetainsOriginalAssessmentAndEvidenceAfterNewGradeAndAdvice(){
        image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));
        long pid=BatchLedger.number(transaction(()->service.generateAdvice(1,adviceInput())).get("id"));
        var original=json.valueToTree(service.processingTrace(1,pid));
        assertEquals("current",original.path("source_status").asText());assertTrue(original.path("record_current").asBoolean());
        var snapshot=original.path("source_assessment").path("snapshot");
        image(12,"b".repeat(64),false);burden(12,"low");transaction(()->service.synchronizeInitial(1));
        transaction(()->service.generateAdvice(1,adviceInput()));
        var historic=json.valueToTree(service.processingTrace(1,pid));
        assertEquals("historical",historic.path("source_status").asText());assertFalse(historic.path("record_current").asBoolean());
        assertEquals(snapshot,historic.path("source_assessment").path("snapshot"));
        assertEquals("A",historic.path("source_assessment").path("final_grade").asText());assertEquals("B",historic.path("current_assessment").path("final_grade").asText());
        assertEquals(1,historic.path("source_assessment").path("snapshot").path("evidence").path("records").size());
        assertEquals(404,assertThrows(BusinessIntegrationFault.class,()->service.processingTrace(2,pid)).status());
    }
    @Test void traceLabelsUnlinkedLegacyAndRejectsMismatchedSource(){
        db.update("INSERT INTO processing_records(batch_id,inputs_json,advice_json) VALUES(1,'{\"grade\":\"B\"}','{}')");
        var legacy=json.valueToTree(service.processingTrace(1,1));assertEquals("unlinked",legacy.path("source_status").asText());assertTrue(legacy.path("source_assessment").isNull());
        image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));
        long pid=BatchLedger.number(transaction(()->service.generateAdvice(1,adviceInput())).get("id"));
        var inputs=(ObjectNode)json.valueToTree(service.processingTrace(1,pid).get("record")).path("inputs");inputs.put("grade","REJECT");
        db.update("UPDATE processing_records SET inputs_json=? WHERE id=?",inputs.toString(),pid);
        assertEquals("SOURCE_LINK_INVALID",assertThrows(BusinessIntegrationFault.class,()->service.processingTrace(1,pid)).code());
    }
    @Test void traceKeepsAdoptionAndExecutionHistoryButInvalidatesChangedParameters(){
        image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));
        long pid=BatchLedger.number(transaction(()->service.generateAdvice(1,adviceInput())).get("id"));
        var a=json.valueToTree(service.view(1).get("assessment"));
        var op=node("kind","sorting_completed","operator","TEST","note","TEST ONLY","performed_at","2026-10-13T10:00:00+08:00");
        op.put("assessment_id",a.path("id").asLong());op.put("assessment_revision",a.path("revision").asLong());op.put("actually_performed_confirmed",true);transaction(()->service.operation(1,op));
        String values="{\"pre_cooling_time\":\"6h\",\"washing_pressure\":\"normal\"}";
        db.update("UPDATE processing_records SET adopted_values_json=? WHERE id=?",values,pid);BatchLedger.recordAdoption(db,1,pid,values,"TEST","first adoption");
        op.put("kind","processing_completed");op.put("request_id",UUID.randomUUID().toString());op.put("processing_id",pid);op.put("expected_processing_fingerprint",BatchLedger.processingFingerprint(db,json,pid));transaction(()->service.operation(1,op));
        assertTrue(json.valueToTree(service.processingTrace(1,pid)).path("operations").get(0).path("current").asBoolean());
        values="{\"pre_cooling_time\":\"7h\",\"washing_pressure\":\"normal\"}";
        db.update("UPDATE processing_records SET adopted_values_json=? WHERE id=?",values,pid);BatchLedger.recordAdoption(db,1,pid,values,"TEST","changed adoption");
        var changed=json.valueToTree(service.processingTrace(1,pid));assertEquals(2,changed.path("adoptions").size());assertEquals(2,changed.path("operations").size());
        assertFalse(changed.path("operations").get(0).path("current").asBoolean());assertFalse(changed.path("operations").get(0).path("parameters_match").asBoolean());
    }
    @Test void adoptionReusesAssessmentMapperForMysqlLocalDateTime() throws Exception {
        json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        db=spy(db);
        doAnswer(invocation->{
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)invocation.callRealMethod();
            for(var row:rows)if(row.get("created_at") instanceof java.sql.Timestamp timestamp)row.put("created_at",timestamp.toLocalDateTime());
            return rows;
        }).when(db).queryForList("SELECT * FROM q4_rounds WHERE batch_id=? ORDER BY id DESC LIMIT 1",1L);
        service=new BatchWorkbenchService(db,json,business);
        image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));
        long pid=BatchLedger.number(transaction(()->service.generateAdvice(1,adviceInput())).get("id"));
        var a=json.valueToTree(service.view(1).get("assessment"));
        var op=node("kind","sorting_completed","operator","TEST","note","TEST FIXTURE","performed_at","2026-10-13T10:00:00+08:00");
        op.put("assessment_id",a.path("id").asLong());op.put("assessment_revision",a.path("revision").asLong());op.put("actually_performed_confirmed",true);transaction(()->service.operation(1,op));
        var adapter=new BusinessIntegrationService(db,json,new DataSourceTransactionManager(db.getDataSource()),"http://127.0.0.1:1");
        var input=node("operator","TEST","note","TEST FIXTURE","expected_processing_fingerprint",BatchLedger.processingFingerprint(db,json,pid));
        input.set("adopted_values",json.readTree("{\"pre_cooling_time\":\"4h\",\"washing_pressure\":\"normal\"}"));
        assertEquals(true,adapter.adoptProcessing(pid,input).get("persisted"));
        assertEquals(1,json.valueToTree(service.processingTrace(1,pid)).path("adoptions").size());
        assertEquals(a.path("evidence_fingerprint"),json.valueToTree(service.view(1).get("assessment")).path("evidence_fingerprint"));
    }
    @Test void processingChangesRequireExplicitRepublication(){
        image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));
        var options=node("operator","TEST");options.put("confirm_public_summary",true);
        options.put("expected_fingerprint",service.preview(1,options).get("source_fingerprint").toString());transaction(()->service.publish(1,options));
        transaction(()->service.generateAdvice(1,adviceInput()));
        var old=json.valueToTree(service.publicView("Q4-TEST-A"));assertFalse(old.path("current").asBoolean());assertEquals("尚无与当前评定关联的工艺建议",old.path("snapshot").path("processing_summary").asText());
        options.put("request_id",UUID.randomUUID().toString());options.put("expected_fingerprint",service.preview(1,options).get("source_fingerprint").toString());transaction(()->service.publish(1,options));
        var published=json.valueToTree(service.publicView("Q4-TEST-A"));assertTrue(published.path("current").asBoolean());assertEquals("已保存工艺建议，尚未采用",published.path("snapshot").path("processing_summary").asText());
    }
    @Test void emptyRoundIsNotGradeA(){start();assertEquals("ADD_IMAGES",service.view(1).get("state"));assertThrows(BusinessIntegrationFault.class,()->assess("A","premium_sorting"));}
    @Test void unreviewedEvidenceAutomaticallyGradesWithoutHuman(){image(11,"a".repeat(64),false);var saved=transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(saved).path("workbench");assertTrue(w.path("assessment_current").asBoolean());assertEquals("A",w.path("assessment").path("final_grade").asText());assertEquals("ai_initial",w.path("assessment").path("source").asText());assertEquals(0,w.path("evidence").path("pending_count").asInt());assertEquals(0,BatchLedger.count(db,"SELECT COUNT(*) FROM inspection_reviews"));transaction(()->service.generateAdvice(1,adviceInput()));verify(business).processingAdvice(eq(1L),argThat(v->"A".equals(v.path("grade").asText())));}
    @Test void twoPhotosSameSampleCountOnce() {long r=start();image(11,"a".repeat(64),true);image(12,"b".repeat(64),true);for(long id:new long[]{11,12}){var n=node("sample_code","APPLE-01");n.put("inspection_id",id);n.put("expected_fingerprint",json.valueToTree(service.view(1).get("evidence")).path("fingerprint").asText());transaction(()->service.bindImage(1,r,n));}var e=json.valueToTree(service.view(1).get("evidence"));assertEquals(2,e.path("unique_photo_count").asInt());assertEquals(1,e.path("identified_sample_count").asInt());assertTrue(e.path("fruit_defect_rate").isNull());}
    @Test void duplicatePixelImagesNotDifferentFruit(){long r=start();image(11,"a".repeat(64),true);image(12,"a".repeat(64),true);for(long id:new long[]{11,12}){var n=node("sample_code",id==11?"ONE":"TWO");n.put("inspection_id",id);n.put("expected_fingerprint",json.valueToTree(service.view(1).get("evidence")).path("fingerprint").asText());if(id==11)transaction(()->service.bindImage(1,r,n));else assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.bindImage(1,r,n)));}assertEquals(1,json.valueToTree(service.view(1).get("evidence")).path("unique_photo_count").asInt());}
    @Test void assessedBIsReadServerSideAndRepeatedCallIdempotent(){start();image(11,"a".repeat(64),true);assess("B","general_sorting");var in=adviceInput();in.put("grade","A");var one=transaction(()->service.generateAdvice(1,in));var two=transaction(()->service.generateAdvice(1,in));assertEquals(one.get("id"),two.get("id"));verify(business,times(1)).processingAdvice(eq(1L),argThat(v->"B".equals(v.path("grade").asText())));var inputs=json.valueToTree(service.view(1).get("processing")).path("inputs");assertEquals("approved_batch_assessment",inputs.path("grade_source").path("type").asText());assertEquals("B",inputs.path("grade").asText());}
    @Test void newImageInvalidatesAssessment(){start();image(11,"a".repeat(64),true);assess("B","general_sorting");var in=adviceInput();image(12,"b".repeat(64),false);assertEquals(false,service.view(1).get("assessment_current"));assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.generateAdvice(1,in)));verifyNoInteractions(business);}
    @Test void reviewRevisionInvalidatesAssessment(){start();image(11,"a".repeat(64),true);assess("B","general_sorting");db.update("INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,candidate_reviews) VALUES(11,2,'no_target_confirmed','Q4 TEST','new version','[]')");assertEquals(false,service.view(1).get("assessment_current"));}
    @Test void cAndRejectKeepBStatuses(){start();image(11,"a".repeat(64),true);assess("C","processing_evaluation");transaction(()->service.generateAdvice(1,adviceInput()));assertEquals("manual_review",json.valueToTree(service.view(1).get("processing")).path("advice_status").asText());assess("REJECT","isolate_return");transaction(()->service.generateAdvice(1,adviceInput()));assertEquals("blocked",json.valueToTree(service.view(1).get("processing")).path("advice_status").asText());}
    @Test void sourceFromOtherBatchRejected(){start();image(11,"a".repeat(64),true);assess("B","general_sorting");var in=adviceInput();assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.generateAdvice(2,in)));verifyNoInteractions(business);}
    @Test void publicSnapshotWhitelistsFieldsAndBecomesStale(){start();image(11,"a".repeat(64),true);assess("B","general_sorting");var options=json.createObjectNode();var preview=service.preview(1,options);var in=node("operator","Q4 TEST");in.put("expected_fingerprint",preview.get("source_fingerprint").toString());in.put("confirm_public_summary",true);transaction(()->service.publish(1,in));var pub=json.valueToTree(service.publicView("Q4-TEST-A"));assertTrue(pub.path("current").asBoolean());String body=pub.path("snapshot").toString();for(String secret:new String[]{"approved_by","reviewer","remark","candidate_reviews","image_hash","Q4 TEST"})assertFalse(body.contains(secret));assertFalse(pub.path("snapshot").has("grade"));image(12,"b".repeat(64),true);assertFalse(json.valueToTree(service.publicView("Q4-TEST-A")).path("current").asBoolean());}
    @Test void sameRequestDifferentInputRejected(){var in=roundInput();transaction(()->service.createRound(1,in));in.put("name","CHANGED");assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.createRound(1,in)));}
    @Test void resolvedAlertDoesNotRemoveOngoingEpisode(){start();db.update("INSERT INTO alerts(batch_id,status) VALUES(1,'resolved')");db.update("INSERT INTO coldchain_episode_records(batch_id,end_reason) VALUES(1,'ongoing')");assertEquals(true,BatchLedger.cold(db,json,1).get("hold"));}
    void burden(long iid,String level) {var p=(ObjectNode)BatchLedger.parse(json,db.queryForObject("SELECT response_json FROM inspection_payloads WHERE inspection_id=?",String.class,iid));p.putObject("defect_burden").put("burden_level",level);db.update("UPDATE inspection_payloads SET response_json=? WHERE inspection_id=?",p.toString(),iid);}
    @Test void worstImageGradeWinsAndSyncIsIdempotent(){image(11,"a".repeat(64),false);burden(11,"low");image(12,"b".repeat(64),false);burden(12,"high");transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(service.view(1));assertEquals("REJECT",w.path("assessment").path("final_grade").asText());assertEquals("isolate_return",w.path("assessment").path("intended_use").asText());assertEquals("REJECTED",w.path("state").asText());transaction(()->service.synchronizeInitial(1));assertEquals(1,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments"));}
    @Test void manualCorrectionSurvivesSynchronization(){image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));assess("B","general_sorting");transaction(()->service.synchronizeInitial(1));var a=json.valueToTree(service.view(1)).path("assessment");assertEquals("B",a.path("final_grade").asText());assertEquals("human_corrected",a.path("source").asText());assertEquals(2,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments"));}
    @Test void newPhotoAppendsInitialAndInvalidatesOldProcessing(){image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));var old=adviceInput();transaction(()->service.generateAdvice(1,old));image(12,"b".repeat(64),false);burden(12,"moderate");transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(service.view(1));assertEquals("C",w.path("assessment").path("final_grade").asText());assertFalse(w.path("processing").path("current").asBoolean());old.put("request_id",UUID.randomUUID().toString());assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.generateAdvice(1,old)));assertEquals(2,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments"));}
    @Test void explicitRecheckBlocksWithoutMandatoryReviewOfOtherImages(){image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));db.update("INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,candidate_reviews) VALUES(11,1,'needs_recheck','TEST','fixture-only','[]')");transaction(()->service.synchronizeInitial(1));assertEquals("REVIEW_IMAGES",service.view(1).get("state"));assertFalse((Boolean)service.view(1).get("assessment_current"));}
    @Test void dispositionClosesRejectWithoutPretendingProcessing(){image(11,"a".repeat(64),false);burden(11,"high");transaction(()->service.synchronizeInitial(1));var a=json.valueToTree(service.view(1)).path("assessment");var n=node("kind","quality_disposition","operator","TEST","note","FIXTURE: isolated, not an actual operation","performed_at","2026-10-13T10:00:00+08:00");n.put("assessment_id",a.path("id").asLong());n.put("assessment_revision",a.path("revision").asLong());n.put("actually_performed_confirmed",true);transaction(()->service.operation(1,n));assertEquals("DISPOSITION_RECORDED",service.view(1).get("state"));assertFalse((Boolean)service.view(1).get("processing_executed"));}
    @Test void oldAIGradeDIsReassessedFromEvidenceWithNewPolicy(){image(11,"a".repeat(64),false);burden(11,"high");transaction(()->service.synchronizeInitial(1));db.update("UPDATE q4_assessments SET final_grade='D',grade_policy_version='ai-batch-initial-v5'");transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(service.view(1));assertEquals("REJECT",w.path("assessment").path("final_grade").asText());assertEquals(2,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments"));assertEquals(1,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments WHERE final_grade='D'"));assertFalse(w.path("quality_disposition_recorded").asBoolean());}
    @Test void oldHumanDIsNotSilentlyTurnedIntoReject(){image(11,"a".repeat(64),false);transaction(()->service.synchronizeInitial(1));assess("B","general_sorting");db.update("UPDATE q4_assessments SET final_grade='D' WHERE revision=2");transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(service.view(1));assertEquals("D",w.path("assessment").path("final_grade").asText());assertFalse(w.path("assessment_current").asBoolean());assertEquals(2,BatchLedger.count(db,"SELECT COUNT(*) FROM q4_assessments"));assertThrows(BusinessIntegrationFault.class,()->transaction(()->service.generateAdvice(1,adviceInput())));}
    @Test void legacyHumanImageDRequiresCorrectionRatherThanAutomaticReject(){image(11,"a".repeat(64),false);db.update("INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,final_grade,candidate_reviews) VALUES(11,1,'manual_target_confirmed','TEST','FIXTURE ONLY','D','[]')");transaction(()->service.synchronizeInitial(1));var w=json.valueToTree(service.view(1));assertEquals(1,w.path("evidence").path("pending_count").asInt());assertTrue(w.path("assessment").isNull());assertEquals("REVIEW_IMAGES",w.path("state").asText());}
}
