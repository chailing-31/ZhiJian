package com.zhijian.demo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping
public class BatchController {
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    public BatchController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static String time(LocalDateTime value) {
        return value == null ? null : value.atZone(DEMO_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static Map<String, Object> batch(ResultSet rs, int ignored) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batch_id", rs.getLong("id"));
        item.put("batch_code", rs.getString("batch_code"));
        item.put("product", rs.getString("product"));
        item.put("variety", rs.getString("variety"));
        item.put("origin", rs.getString("origin"));
        item.put("supplier", rs.getString("supplier"));
        item.put("status", rs.getString("status"));
        item.put("created_at", time(rs.getObject("created_at", LocalDateTime.class)));
        return item;
    }

    private static final RowMapper<Map<String, Object>> EVENT_MAPPER = (rs, ignored) -> {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", rs.getString("event_type"));
        event.put("event_time", time(rs.getObject("event_time", LocalDateTime.class)));
        event.put("summary", rs.getString("summary"));
        event.put("source", rs.getString("source"));
        return event;
    };

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/batches")
    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT * FROM batches ORDER BY id DESC", BatchController::batch);
    }

    @GetMapping("/batches/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        List<Map<String, Object>> found = jdbc.query("SELECT * FROM batches WHERE id = ?", BatchController::batch, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");
        Map<String, Object> result = new LinkedHashMap<>(found.get(0));
        result.put("events", jdbc.query("SELECT event_type,event_time,summary,source FROM batch_events WHERE batch_id = ? ORDER BY event_time,id", EVENT_MAPPER, id));
        return result;
    }

    public record CreateBatch(String batch_code, String product, String variety, String origin, String supplier) {}

    @PostMapping("/batches")
    public Map<String, Object> create(@RequestBody CreateBatch input) {
        String code = input.batch_code() == null ? "" : input.batch_code().trim();
        if (!code.matches("[A-Za-z0-9-]{1,64}") || input.product() == null || input.product().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch_code and product are required");
        }
        var key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO batches(batch_code,product,variety,origin,supplier,status) VALUES(?,?,?,?,?,'created')", new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, input.product().trim());
                ps.setString(3, input.variety());
                ps.setString(4, input.origin());
                ps.setString(5, input.supplier());
                return ps;
            }, key);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch code already exists");
        }
        return Map.of("batch_id", Objects.requireNonNull(key.getKey()).longValue(), "batch_code", code);
    }

    @GetMapping("/trace/{batchCode}")
    public Map<String, Object> trace(@PathVariable String batchCode) {
        List<Map<String, Object>> found = jdbc.query("SELECT * FROM batches WHERE batch_code = ?", BatchController::batch, batchCode);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");
        var batch = found.get(0);

        List<Map<String, Object>> events = jdbc.query(
            "SELECT event_type,event_time,summary,source FROM batch_events WHERE batch_id = ? AND visibility = 'public' ORDER BY event_time,id",
            EVENT_MAPPER, batch.get("batch_id"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch_code", batch.get("batch_code"));
        result.put("product", batch.get("product"));
        result.put("variety", batch.get("variety"));
        result.put("origin", batch.get("origin"));
        result.put("public_summary", summarizePublicEvents(events));
        result.put("events", events);
        return result;
    }

    /**
     * Summaries are derived only from already-public batch_events.
     * They mean "public records exist", never "this stage is complete/safe/passed".
     */
    private static Map<String, Object> summarizePublicEvents(List<Map<String, Object>> events) {
        Map<String, StageCounter> counters = new LinkedHashMap<>();
        counters.put("inspection", new StageCounter());
        counters.put("processing", new StageCounter());
        counters.put("coldchain", new StageCounter());
        counters.put("logistics", new StageCounter());

        for (Map<String, Object> event : events) {
            String type = String.valueOf(event.getOrDefault("event_type", ""));
            String stage = publicStage(type);
            if (stage == null) continue;

            StageCounter counter = counters.get(stage);
            counter.recordCount++;
            Object eventTime = event.get("event_time");
            counter.latestEventTime = eventTime == null ? null : String.valueOf(eventTime);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        counters.forEach((key, counter) -> summary.put(key, counter.asMap()));
        return summary;
    }

    private static String publicStage(String eventType) {
        if (eventType.contains("质检") || eventType.contains("复核")) return "inspection";
        if (eventType.contains("加工")) return "processing";
        if (eventType.contains("冷链") || eventType.contains("仓储")) return "coldchain";
        if (eventType.contains("包装") || eventType.contains("出厂")
            || (eventType.contains("运输") && !eventType.contains("冷链"))) return "logistics";
        return null;
    }

    private static final class StageCounter {
        private int recordCount;
        private String latestEventTime;

        private Map<String, Object> asMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("record_count", recordCount);
            item.put("latest_event_time", latestEventTime);
            item.put("state", recordCount > 0 ? "recorded" : "no_public_record");
            return item;
        }
    }
}
