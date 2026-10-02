package com.zhijian.demo.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:i1report;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql("/report-test-schema.sql")
class BatchReportControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void aggregatesSavedRecordsWithoutInventingConclusions() throws Exception {
        mvc.perform(get("/batches/1/report"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema_version").value("zhijian.batch.report.v1"))
            .andExpect(jsonPath("$.scope").value("INTERNAL_DEMO_AGGREGATION_ONLY"))
            .andExpect(jsonPath("$.batch.batch_code").value("APPLE-2026-001"))
            .andExpect(jsonPath("$.inspection.count").value(1))
            .andExpect(jsonPath("$.inspection.reviewed_count").value(1))
            .andExpect(jsonPath("$.inspection.records[0].candidate_count").value(2))
            .andExpect(jsonPath("$.inspection.records[0].review_revision").value(1))
            .andExpect(jsonPath("$.inspection.records[0].latest_review.conclusion").value("target_confirmed"))
            .andExpect(jsonPath("$.processing.count").value(1))
            .andExpect(jsonPath("$.processing.records[0].advice_type").value("rule"))
            .andExpect(jsonPath("$.coldchain.summary.reading_count").value(2))
            .andExpect(jsonPath("$.coldchain.alert_count").value(1))
            .andExpect(jsonPath("$.events.length()").value(2))
            .andExpect(jsonPath("$.data_availability.inspection").value(true))
            .andExpect(jsonPath("$.data_availability.processing").value(true))
            .andExpect(jsonPath("$.warnings.length()").value(0))
            .andExpect(jsonPath("$.disclaimer").exists());
    }

    @Test
    void missingBatchReturns404() throws Exception {
        mvc.perform(get("/batches/999/report")).andExpect(status().isNotFound());
    }
}
