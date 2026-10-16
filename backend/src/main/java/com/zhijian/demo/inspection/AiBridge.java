package com.zhijian.demo.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.UUID;

@Component
@Profile("inspection")
public class AiBridge {
    private final AiHttpTransport transport;
    private final ObjectMapper mapper;
    public AiBridge(@Value("${zhijian.inspection.ai-base-url:http://127.0.0.1:8001}") String origin, ObjectMapper mapper) {
        this.transport = new AiHttpTransport(origin, 90000); this.mapper = mapper;
    }
    public boolean ready() {
        try { var r = transport.ready(); return r.status() == 200 && mapper.readTree(r.body()).path("model_ready").asBoolean(); }
        catch (IOException | RuntimeException e) { return false; }
    }
    public JsonNode predict(long batchId, String code, byte[] image, String mime) {
        try {
            var r = transport.predict(batchId, code, image, mime);
            if (r.status() == 413) throw new InspectionFault(413, "IMAGE_TOO_LARGE", "AI 服务拒绝超出限制的图片。");
            if (r.status() == 422) throw new InspectionFault(422, "INVALID_IMAGE", "AI 服务拒绝此图片，请检查格式、尺寸和内容。");
            if (r.status() == 503) throw new InspectionFault(503, "AI_NOT_READY", "8001 模型未就绪或推理失败；请查看 AI 服务窗口。");
            if (r.status() == 507) throw new InspectionFault(507, "AI_STORAGE_FAILED", "AI 服务证据保存失败，请检查数据盘。");
            if (r.status() != 200) throw new InspectionFault(502, "AI_HTTP_ERROR", "AI 服务响应异常；未生成业务记录。");
            if (r.contentType() == null || !r.contentType().startsWith("application/json")) throw new IOException("Not JSON");
            return mapper.readTree(r.body());
        } catch (SocketTimeoutException e) { throw new InspectionFault(504, "AI_TIMEOUT", "AI 请求超时。先刷新历史记录，不要重复点击检测。"); }
        catch (IOException e) { throw new InspectionFault(502, "AI_UNREACHABLE", "无法调用 8001 AI 服务，请确认模型就绪且服务窗口保持运行。"); }
    }
    public void download(UUID prediction, String variant, Path target) {
        try { transport.downloadPng(prediction, variant, target); }
        catch (IOException e) { throw new InspectionFault(502, "ARTIFACT_FETCH_FAILED", "无法从 AI 服务取得结果图；未保存业务成功记录。"); }
    }
}
