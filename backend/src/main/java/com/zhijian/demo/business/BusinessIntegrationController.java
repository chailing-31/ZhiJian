package com.zhijian.demo.business;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * C-owned adapter: the browser talks to Spring Boot, never to the B service.
 */
@RestController
public class BusinessIntegrationController {
    private final BusinessIntegrationService service;

    public BusinessIntegrationController(BusinessIntegrationService service) {
        this.service = service;
    }

    @PostMapping("/batches/{batchId}/processing-advice")
    public Map<String, Object> processingAdvice(@PathVariable long batchId, @RequestBody JsonNode input) {
        return service.processingAdvice(batchId, input);
    }

    @PostMapping("/processing-records/{recordId}/adopt")
    public Map<String, Object> adoptProcessing(@PathVariable long recordId, @RequestBody JsonNode input) {
        return service.adoptProcessing(recordId, input);
    }

    @GetMapping("/batches/{batchId}/processing-records")
    public Map<String, Object> processingHistory(@PathVariable long batchId) {
        return service.processingHistory(batchId);
    }

    @PostMapping("/batches/{batchId}/sensor-readings")
    public Map<String, Object> sensorReading(@PathVariable long batchId, @RequestBody JsonNode input) {
        return service.sensorReading(batchId, input);
    }

    @GetMapping("/batches/{batchId}/coldchain")
    public Map<String, Object> coldchain(@PathVariable long batchId) {
        return service.coldchain(batchId);
    }

    @PostMapping({"/alerts/{alertId}/resolve", "/alert/{alertId}/resolve"})
    public Map<String, Object> resolveAlert(@PathVariable long alertId, @RequestBody JsonNode input) {
        return service.resolveAlert(alertId, input);
    }

    @GetMapping("/business-service/ready")
    public Map<String, Object> ready() {
        return service.ready();
    }
}
