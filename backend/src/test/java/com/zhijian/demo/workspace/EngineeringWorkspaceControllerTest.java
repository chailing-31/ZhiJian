package com.zhijian.demo.workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhijian.demo.business.BusinessIntegrationService;
import com.zhijian.demo.inspection.InspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EngineeringWorkspaceControllerTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper();
    final InspectionService inspections=mock(InspectionService.class);
    final BusinessIntegrationService business=mock(BusinessIntegrationService.class);
    final EngineeringWorkspaceController api=new EngineeringWorkspaceController(jdbc,json,inspections,business);
    ObjectNode input() {
        ObjectNode n=json.createObjectNode();n.put("inspection_id",11);n.put("expected_review_revision",1);
        n.put("operator","Test");n.put("use_as_material_grade_confirmed",true);n.put("temperature",5);n.put("humidity",80);return n;
    }
    void source(String grade,String conclusion,int revision) {
        when(jdbc.queryForObject("SELECT MAX(i.id) FROM inspections i JOIN inspection_payloads p ON p.inspection_id=i.id WHERE i.batch_id=?",Long.class,1L)).thenReturn(11L);
        when(jdbc.queryForList("SELECT id FROM inspections WHERE id=? AND batch_id=? FOR UPDATE",Long.class,11L,1L)).thenReturn(List.of(11L));
        when(inspections.detail(11L)).thenReturn(Map.of("batch_id",1L,"review_revision",revision,"latest_review",Map.of("revision",revision,"conclusion",conclusion,"final_grade",grade,"reviewer","Reviewer")));
    }
    @Test void noApprovalIsRejected() {
        ObjectNode n=input();n.put("use_as_material_grade_confirmed",false);
        assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,n));verifyNoInteractions(business);
    }
    @Test void unreviewedSourceIsRejected() {
        when(jdbc.queryForList("SELECT id FROM inspections WHERE id=? AND batch_id=? FOR UPDATE",Long.class,11L,1L)).thenReturn(List.of(11L));
        when(inspections.detail(11L)).thenReturn(Map.of("batch_id",1L,"review_revision",0));
        assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,input()));verifyNoInteractions(business);
    }
    @Test void staleRevisionIsRejected() {source("B","no_target_confirmed",2);assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,input()));verifyNoInteractions(business);}
    @Test void dIsNotMapped() {source("D","target_confirmed",1);assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,input()));verifyNoInteractions(business);}
    @Test void pendingReviewIsRejected() {source("B","needs_recheck",1);assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,input()));verifyNoInteractions(business);}
    @Test void nonIntegralIdIsRejected() {ObjectNode n=input();n.put("inspection_id",11.5);assertThrows(EngineeringWorkspaceController.WorkspaceFault.class,()->api.fromReview(1,n));verifyNoInteractions(business);}
    @Test void happyPathUsesSavedGradeAndPersistsProvenance() {
        source("B","no_target_confirmed",1);
        when(business.processingAdvice(eq(1L),any())).thenReturn(Map.of("record_id",21L,"inputs",Map.of("batch_id",1L,"grade","B"),"advice",Map.of("status","suggested"),"persisted",true));
        when(jdbc.update(eq("UPDATE processing_records SET inputs_json=? WHERE id=? AND batch_id=?"),anyString(),eq(21L),eq(1L))).thenReturn(1);
        ObjectNode n=input();n.put("grade","A"); // Client-supplied grade must be ignored.
        var result=api.fromReview(1,n);
        var stored=json.valueToTree(result.get("inputs"));assertEquals("B",stored.path("grade").asText());assertEquals(11,stored.path("grade_source").path("inspection_id").asInt());
        verify(business).processingAdvice(eq(1L),argThat(v->v.path("grade").asText().equals("B")));
    }
}
