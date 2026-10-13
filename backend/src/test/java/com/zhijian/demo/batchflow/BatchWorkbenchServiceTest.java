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
        db.update("INSERT INTO inspections(id,batch_id) VALUES(?,1)",id);ObjectNode p=json.createObjectNode();p.putObject("image").put("normalized_pixels_sha256",hash);p.put("model_version","fixture");p.putArray("detections");
        db.update("INSERT INTO inspection_payloads(inspection_id,response_json) VALUES(?,?)",id,p.toString());
        transaction(()->{BatchLedger.enrollUpload(db,1,id);return null;});
        if(reviewed)db.update("INSERT INTO inspection_reviews(inspection_id,revision,conclusion,reviewer,remark,candidate_reviews) VALUES(?,1,'no_target_confirmed','Q4 TEST','MOCK REVIEW ONLY','[]')",id);
    }
    Map<String,Object> assess(String grade,String use){var w=service.view(1);var r=json.valueToTree(w.get("round"));var a=json.valueToTree(w.get("assessment"));var n=node("final_grade",grade,"intended_use",use,"grade_basis","TEST FIXTURE ONLY: not an actual batch quality judgment","operator","Q4 TEST");n.put("round_id",r.path("id").asLong());n.put("expected_assessment_id",a.path("id").asLong());n.put("expected_fingerprint",json.valueToTree(w.get("evidence")).path("fingerprint").asText());n.put("scope_confirmed",true);n.put("use_confirmed",true);return transaction(()->service.assess(1,n));}
    ObjectNode adviceInput(){var a=json.valueToTree(service.view(1).get("assessment"));var n=node("operator","Q4 TEST");n.put("assessment_id",a.path("id").asLong());n.put("assessment_revision",a.path("revision").asLong());n.put("use_as_process_input_confirmed",true);n.put("temperature",5);n.put("humidity",80);return n;}
    @Test void emptyRoundIsNotGradeA(){start();assertEquals("ADD_IMAGES",service.view(1).get("state"));assertThrows(BusinessIntegrationFault.class,()->assess("A","premium_sorting"));}
    @Test void unreviewedEvidenceCannotBeApproved(){start();image(11,"a".repeat(64),false);assertThrows(BusinessIntegrationFault.class,()->assess("B","general_sorting"));}
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
}
