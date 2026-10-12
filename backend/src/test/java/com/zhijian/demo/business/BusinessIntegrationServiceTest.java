package com.zhijian.demo.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessIntegrationServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private BusinessIntegrationService service;
    private HttpServer server;
    private volatile boolean unavailable;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:c" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE batches(id BIGINT PRIMARY KEY,batch_code VARCHAR(64))");
        jdbc.execute("INSERT INTO batches VALUES(1,'C-TEST')");
        jdbc.execute("CREATE TABLE sensor_readings(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,timestamp TIMESTAMP,temperature REAL,humidity REAL,door_open BOOLEAN,equipment_current REAL,source VARCHAR(30))");
        jdbc.execute("CREATE TABLE processing_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,inputs_json VARCHAR,advice_json VARCHAR,advice_type VARCHAR(30),operator VARCHAR(50),adopted_values_json VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE batch_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,event_type VARCHAR(50),event_time TIMESTAMP,summary VARCHAR,source VARCHAR,operator VARCHAR,visibility VARCHAR)");
        jdbc.execute("CREATE TABLE alerts(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,started_at TIMESTAMP,level VARCHAR,reason VARCHAR,trigger_value VARCHAR,status VARCHAR,resolved_by VARCHAR,resolved_at TIMESTAMP,resolution VARCHAR)");
        jdbc.execute("CREATE TABLE coldchain_episode_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,batch_id BIGINT,source VARCHAR,episode_key VARCHAR UNIQUE,alert_id BIGINT,started_at TIMESTAMP,triggered_at TIMESTAMP,last_observed_at TIMESTAMP,recovered_at TIMESTAMP,end_reason VARCHAR,trigger_value DOUBLE,observed_minutes DOUBLE,peak_temperature DOUBLE,level VARCHAR,current_status VARCHAR,reason VARCHAR,rule_version VARCHAR,config_json VARCHAR)");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            Map<String,Object> response;
            if (exchange.getRequestURI().getPath().contains("process-advice")) {
                String grade = request.path("grade").asText();
                response = Map.of("status", grade.equals("REJECT") ? "blocked" : "suggested", "advice_type", "rule", "advice", Map.of("pre_cooling_time", "6h", "washing_pressure", "normal"));
            } else {
                JsonNode readings = request.path("readings");
                JsonNode last = readings.get(readings.size()-1);
                boolean active = last.path("temperature").asDouble() > 8;
                var episode = new LinkedHashMap<String,Object>();
                episode.put("started_at", readings.get(0).path("timestamp").asText());
                episode.put("triggered_at", readings.get(0).path("timestamp").asText());
                episode.put("last_observed_at", last.path("timestamp").asText());
                episode.put("recovered_at", active ? null : last.path("timestamp").asText());
                episode.put("end_reason", active ? "ongoing" : "recovered");
                episode.put("trigger_value", 12); episode.put("observed_minutes", 5); episode.put("peak_temperature", 12);
                response = Map.of("rule_version", "test-v1", "level", active ? "HIGH" : "NONE", "reason", "current only", "episodes", List.of(episode), "config", Map.of("temperature_upper",8,"duration_minutes",5,"max_gap_minutes",2));
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        service = new BusinessIntegrationService(jdbc, json, new DataSourceTransactionManager(ds), "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void close() { server.stop(0); }
    private JsonNode reading(String time, int temperature) throws Exception {
        return json.readTree("{\"timestamp\":\"2026-10-12T"+time+"+08:00\",\"temperature\":"+temperature+",\"humidity\":80,\"source\":\"simulation\"}");
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table, Long.class); }

    @Test void firstEpisodePersistsAndRecoveryPreservesSeverityAndManualResolution() throws Exception {
        service.sensorReading(1, reading("10:00:00", 12));
        assertEquals(1, count("alerts"));
        assertEquals(1, count("coldchain_episode_records"));
        var recover = reading("10:06:00",4);
        ((com.fasterxml.jackson.databind.node.ObjectNode)recover).set("config",json.readTree("{\"max_gap_minutes\":2,\"temperature_upper\":8,\"duration_minutes\":5}"));
        service.sensorReading(1, recover);
        assertEquals(1, count("alerts"));
        assertEquals("HIGH",jdbc.queryForObject("SELECT level FROM alerts",String.class));
        assertEquals("observed",jdbc.queryForObject("SELECT status FROM alerts",String.class));
        service.resolveAlert(1,json.readTree("{\"resolved_by\":\"tester\",\"resolution\":\"checked\"}"));
        service.sensorReading(1,recover);
        assertEquals("resolved",jdbc.queryForObject("SELECT status FROM alerts",String.class));
        service.resolveAlert(1,json.readTree("{\"resolved_by\":\"tester\",\"resolution\":\"checked\"}"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM batch_events WHERE event_type='冷链处置'",Integer.class));
    }
    @Test void duplicateIsIdempotentAndConflictDoesNotOverwrite() throws Exception {
        var input=reading("10:00:00",12);
        service.sensorReading(1,input);
        assertEquals(true,service.sensorReading(1,input).get("duplicate"));
        BusinessIntegrationFault error=assertThrows(BusinessIntegrationFault.class,()->service.sensorReading(1,reading("10:00:00",9)));
        assertEquals(409,error.status()); assertEquals(1,count("sensor_readings")); assertEquals(1,count("alerts"));
    }
    @Test void businessFailureRollsBackReading() throws Exception {
        unavailable=true;
        assertThrows(BusinessIntegrationFault.class,()->service.sensorReading(1,reading("10:00:00",12)));
        assertEquals(0,count("sensor_readings")); assertEquals(0,count("alerts"));
    }
    @Test void invalidDoorAndTooLargeHistoryAreRejected() throws Exception {
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)reading("10:00:00",12);
        input.put("door_open","false");
        assertEquals(400,assertThrows(BusinessIntegrationFault.class,()->service.sensorReading(1,input)).status());
        jdbc.execute("INSERT INTO sensor_readings(batch_id,timestamp,temperature,humidity,source) SELECT 1,DATEADD('SECOND', X, TIMESTAMP '2026-01-01 00:00:00'),4,80,'simulation' FROM SYSTEM_RANGE(1,10000)");
        assertEquals(409,assertThrows(BusinessIntegrationFault.class,()->service.sensorReading(1,reading("10:00:00",12))).status());
        assertEquals(10000,count("sensor_readings"));
    }
    @Test void concurrentDuplicateWritesCreateOneReadingAndEpisode() throws Exception {
        var input=reading("10:00:00",12);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=List.<Callable<Map<String,Object>>>of(()->service.sensorReading(1,input),()->service.sensorReading(1,input));
            for(var future:pool.invokeAll(tasks)) future.get(10,TimeUnit.SECONDS);
            assertEquals(1,count("sensor_readings")); assertEquals(1,count("alerts"));
        } finally { pool.shutdownNow(); }
    }
    @Test void processingCanBeReadAndEditedButBlockedAdviceCannotBeAdopted() throws Exception {
        var result=service.processingAdvice(1,json.readTree("{\"grade\":\"B\",\"temperature\":5,\"humidity\":80}"));
        long id=((Number)result.get("record_id")).longValue();
        service.adoptProcessing(id,json.readTree("{\"operator\":\"tester\",\"adopted_values\":{\"pre_cooling_time\":\"7h\",\"washing_pressure\":\"manual\"}}"));
        var records=(List<?>)service.processingHistory(1).get("records");
        assertEquals(1,records.size());
        assertEquals("7h",((JsonNode)((Map<?,?>)records.get(0)).get("adopted_values")).path("pre_cooling_time").asText());
        var blocked=service.processingAdvice(1,json.readTree("{\"grade\":\"REJECT\",\"temperature\":5,\"humidity\":80}"));
        assertEquals(409,assertThrows(BusinessIntegrationFault.class,()->service.adoptProcessing(((Number)blocked.get("record_id")).longValue(),json.readTree("{\"operator\":\"tester\",\"adopted_values\":{\"pre_cooling_time\":\"1h\",\"washing_pressure\":\"normal\"}}"))).status());
    }
}
