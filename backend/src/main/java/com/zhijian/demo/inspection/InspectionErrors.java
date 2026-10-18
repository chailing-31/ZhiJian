package com.zhijian.demo.inspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice(assignableTypes=InspectionController.class)
@Profile("inspection")
public class InspectionErrors {
    private static final Logger LOG = LoggerFactory.getLogger(InspectionErrors.class);
    private static ResponseEntity<Map<String, String>> reply(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
    @ExceptionHandler(InspectionFault.class)
    public ResponseEntity<Map<String, String>> fault(InspectionFault e) { return reply(e.status, e.code, e.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> validation(IllegalArgumentException e) { return reply(400, "INVALID_REVIEW", e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(Exception e) { return reply(413, "IMAGE_TOO_LARGE", "图片不能超过 10 MiB，请压缩后重试。"); }
    @ExceptionHandler({MissingServletRequestPartException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> malformed(Exception e) { return reply(400, "INVALID_REQUEST", "请求字段缺失或格式错误。"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unknown(Exception e) {
        LOG.error("Inspection request failed", e);
        return reply(500, "INSPECTION_FAILED", "质检请求未完成，请查看 Spring Boot 日志；先刷新历史，不要连续重复提交。");
    }
}
