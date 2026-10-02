package com.zhijian.demo.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 + Mock AI integration tests. This is not a real MySQL/YOLO benchmark. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a3;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("inspection")
@Sql("/inspection-test-schema.sql")
class InspectionFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean AiBridge ai;
    static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        if(storage==null) storage=Files.createTempDirectory("a3-mvc-");
        registry.add("zhijian.inspection.storage-root", () -> storage.toString());
    }
    private static final byte[] IMAGE = {1,2,3,4};
    private MockMultipartFile file() { return new MockMultipartFile("image","a.png","image/png",IMAGE); }
    @BeforeEach void setup() throws Exception {
        when(ai.ready()).thenReturn(true);
        when(ai.predict(anyLong(),anyString(),any(),anyString())).thenAnswer(call -> prediction(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        doAnswer(call -> { Files.write((Path)call.getArgument(2),new byte[]{(byte)137,80,78,71,13,10,26,10}); return null; }).when(ai).download(any(),anyString(),any());
    }
    private ObjectNode prediction(long id,String code,byte[] image) throws Exception {
        ObjectNode p=mapper.createObjectNode(); p.put("schema_version","zhijian.ai.inspection.v0.1");
        p.put("prediction_id",UUID.randomUUID().toString()); p.put("batch_id",id); p.put("batch_code",code);
        p.put("model_version","test-only-model"); p.put("weights_sha256","a".repeat(64)); p.put("evaluation_status","not_evaluated");
        p.put("executed_at","2026-10-01T20:00:00+08:00"); p.put("inference_ms",1.0); p.put("confidence_threshold",0.25); p.put("iou_threshold",0.7);
        var im=p.putObject("image"); im.put("width",100); im.put("height",100); im.put("source_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image))); im.put("normalized_pixels_sha256","b".repeat(64)); im.put("coordinate_system","exif_transposed_pixels_xyxy");
        p.putArray("detections"); p.put("observation","no_target_defect_detected"); p.putNull("suggested_grade"); p.put("grade_status","grading_rule_not_configured"); p.put("requires_human_review",true); p.putArray("warnings").add("Test substitute, no actual model."); return p;
    }
    private String upload(String key) throws Exception { return mvc.perform(multipart("/batches/1/inspections").file(file()).header("Idempotency-Key",key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }
    @Test void savesOnceAndPreservesModel() throws Exception {
        String key=UUID.randomUUID().toString(); var r=mapper.readTree(upload(key)); long id=r.path("inspection_id").asLong();
        assertEquals(id,mapper.readTree(upload(key)).path("inspection_id").asLong());
        verify(ai,times(1)).predict(anyLong(),anyString(),any(),anyString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inspections",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM batch_events WHERE visibility='private'",Integer.class));
        assertFalse(r.path("prediction").has("artifacts"));
        mvc.perform(get("/inspections/"+id+"/artifacts/input")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/inspections/"+id+"/artifacts/source")).andExpect(status().isNotFound());
    }
    @Test void appendReviewAndRejectStaleVersion() throws Exception {
        long id=mapper.readTree(upload(UUID.randomUUID().toString())).path("inspection_id").asLong();
        String before=jdbc.queryForObject("SELECT response_json FROM inspection_payloads WHERE inspection_id=?",String.class,id);
        String body="{\"expected_revision\":0,\"reviewer\":\"tester\",\"conclusion\":\"needs_recheck\",\"remark\":\"Need more samples\",\"publish_summary\":true,\"candidate_reviews\":[]}";
        mvc.perform(patch("/inspections/"+id+"/review").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.review_revision").value(1));
        mvc.perform(patch("/inspections/"+id+"/review").contentType("application/json").content(body)).andExpect(status().isConflict());
        assertEquals(before,jdbc.queryForObject("SELECT response_json FROM inspection_payloads WHERE inspection_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inspection_reviews",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM batch_events WHERE visibility='public'",Integer.class));
        assertEquals("created",jdbc.queryForObject("SELECT status FROM batches WHERE id=1",String.class));
    }
    @Test void missingBatchNeverCallsAI() throws Exception {
        mvc.perform(multipart("/batches/999/inspections").file(file()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isNotFound());
        verify(ai,never()).predict(anyLong(),anyString(),any(),anyString());
    }
    @Test void upstreamFailureDoesNotCreateBusinessSuccess() throws Exception {
        doThrow(new InspectionFault(503,"AI_NOT_READY","test")).when(ai).predict(anyLong(),anyString(),any(),anyString());
        mvc.perform(multipart("/batches/1/inspections").file(file()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isServiceUnavailable());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM inspections",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM batch_events",Integer.class));
    }
    @Test void wrongBatchInPredictionIsRejected() throws Exception {
        doReturn(prediction(9,"OTHER",IMAGE)).when(ai).predict(anyLong(),anyString(),any(),anyString());
        mvc.perform(multipart("/batches/1/inspections").file(file()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isBadGateway());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM inspections",Integer.class));
    }
    @Test void failedArtifactDoesNotWriteDatabase() throws Exception {
        doThrow(new InspectionFault(502,"ARTIFACT_FETCH_FAILED","test")).when(ai).download(any(),anyString(),any());
        mvc.perform(multipart("/batches/1/inspections").file(file()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isBadGateway());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM inspections",Integer.class));
    }
    @Test void schemaReadinessAndHistoricalList() throws Exception {
        mvc.perform(get("/inspection-service/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        mvc.perform(get("/batches/1/inspections")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/inspections/999")).andExpect(status().isNotFound());
    }
    @Test void requestKeyCannotBeReusedForDifferentImage() throws Exception {
        String key=UUID.randomUUID().toString(); upload(key);
        mvc.perform(multipart("/batches/1/inspections").file(new MockMultipartFile("image","b.png","image/png",new byte[]{9})).header("Idempotency-Key",key)).andExpect(status().isConflict());
    }
}
