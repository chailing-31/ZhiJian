package com.zhijian.demo.inspection;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@RestController
@Profile("inspection")
public class InspectionController {
    private final InspectionService service;
    public InspectionController(InspectionService service) { this.service = service; }
    @GetMapping("/inspection-service/ready")
    public Map<String, Object> ready() { return service.ready(); }
    @GetMapping("/batches/{batchId}/inspections")
    public List<Map<String, Object>> list(@PathVariable long batchId) { return service.list(batchId); }
    @PostMapping(value="/batches/{batchId}/inspections", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@PathVariable long batchId,
        @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestPart("image") MultipartFile image) {
        return service.create(batchId, key, image);
    }
    @GetMapping("/inspections/{id}")
    public Map<String, Object> detail(@PathVariable long id) { return service.detail(id); }
    @PatchMapping("/inspections/{id}/review")
    public Map<String, Object> review(@PathVariable long id, @RequestBody ReviewRules.Input input) { return service.review(id, input); }
    @GetMapping("/inspections/{id}/artifacts/{variant}")
    public ResponseEntity<FileSystemResource> artifact(@PathVariable long id, @PathVariable String variant) throws IOException {
        var file = service.artifact(id, variant);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(Files.size(file))
            .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(file));
    }
}
