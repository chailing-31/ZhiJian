package com.zhijian.demo.business;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = BusinessIntegrationController.class)
public class BusinessIntegrationErrors {
    @ExceptionHandler(BusinessIntegrationFault.class)
    public ResponseEntity<Map<String, String>> fault(BusinessIntegrationFault error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
