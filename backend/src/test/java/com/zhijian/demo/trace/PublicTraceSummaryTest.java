package com.zhijian.demo.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * H2 contract test for the public trace boundary.
 * Private events and internal batch fields must not leak into the public response.
 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:i2trace;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql(scripts="/trace-test-schema.sql", config=@SqlConfig(encoding="UTF-8"))
class PublicTraceSummaryTest {
    @Autowired MockMvc mvc;

    @Test
    void publicSummaryUsesOnlyPublicEvents() throws Exception {
        mvc.perform(get("/trace/APPLE-2026-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.batch_code").value("APPLE-2026-001"))
            .andExpect(jsonPath("$.supplier").doesNotExist())
            .andExpect(jsonPath("$.batch_id").doesNotExist())
            .andExpect(jsonPath("$.events.length()").value(4))
            .andExpect(jsonPath("$.public_summary.inspection.record_count").value(1))
            .andExpect(jsonPath("$.public_summary.inspection.state").value("recorded"))
            .andExpect(jsonPath("$.public_summary.processing.record_count").value(1))
            .andExpect(jsonPath("$.public_summary.coldchain.record_count").value(1))
            .andExpect(jsonPath("$.public_summary.logistics.record_count").value(0))
            .andExpect(jsonPath("$.public_summary.logistics.state").value("no_public_record"))
            .andExpect(jsonPath("$.events[0].event_type").value("入厂"));
    }

    @Test
    void missingBatchReturns404() throws Exception {
        mvc.perform(get("/trace/UNKNOWN-1"))
            .andExpect(status().isNotFound());
    }
}
