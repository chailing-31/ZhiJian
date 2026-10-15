package com.zhijian.demo.batchflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhijian.demo.business.BusinessIntegrationFault;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@Profile("inspection")
public class BatchWorkbenchController {
    private final BatchWorkbenchService service;
    public BatchWorkbenchController(BatchWorkbenchService service){this.service=service;}
    @GetMapping("/workspace/dashboard") public Map<String,Object> dashboard(){return service.dashboard();}
    @GetMapping("/batches/{id}/batch-workbench") public Map<String,Object> view(@PathVariable long id){return service.view(id);}
    @GetMapping("/batches/{id}/processing-records/{recordId}/trace") public Map<String,Object> processingTrace(@PathVariable long id,@PathVariable long recordId){return service.processingTrace(id,recordId);}
    @PostMapping("/batches/{id}/auto-assessment") public Map<String,Object> initial(@PathVariable long id){return service.synchronizeInitial(id);}
    @PostMapping("/batches/{id}/inspection-rounds") public Map<String,Object> round(@PathVariable long id,@RequestBody JsonNode in){return service.createRound(id,in);}
    @PostMapping("/batches/{id}/inspection-rounds/{round}/images") public Map<String,Object> image(@PathVariable long id,@PathVariable long round,@RequestBody JsonNode in){return service.bindImage(id,round,in);}
    @PostMapping("/batches/{id}/batch-assessments") public Map<String,Object> assessment(@PathVariable long id,@RequestBody JsonNode in){return service.assess(id,in);}
    @PostMapping("/batches/{id}/assessment-processing-advice") public Map<String,Object> advice(@PathVariable long id,@RequestBody JsonNode in){return service.generateAdvice(id,in);}
    @PostMapping("/batches/{id}/batch-operations") public Map<String,Object> operation(@PathVariable long id,@RequestBody JsonNode in){return service.operation(id,in);}
    @PostMapping("/batches/{id}/batch-summary/preview") public Map<String,Object> preview(@PathVariable long id,@RequestBody JsonNode in){return service.preview(id,in);}
    @PostMapping("/batches/{id}/batch-summary/publish") public Map<String,Object> publish(@PathVariable long id,@RequestBody JsonNode in){return service.publish(id,in);}
    @GetMapping("/trace/{code}/batch-summary") public Map<String,Object> publicView(@PathVariable String code){return service.publicView(code);}
    @ExceptionHandler(BusinessIntegrationFault.class) public ResponseEntity<Map<String,String>> failure(BusinessIntegrationFault e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
}
