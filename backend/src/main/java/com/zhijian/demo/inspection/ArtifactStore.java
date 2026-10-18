package com.zhijian.demo.inspection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("inspection")
public class ArtifactStore {
    private final Path root;
    public ArtifactStore(@Value("${zhijian.inspection.storage-root:./var/inspection-artifacts}") String path) throws IOException {
        root = Path.of(path).toAbsolutePath().normalize(); Files.createDirectories(root);
    }
    public Path tempDirectory() throws IOException { return Files.createDirectories(root.resolve("multipart-tmp")); }
    public void capture(UUID id, AiBridge bridge) {
        Path dir = root.resolve(id.toString());
        try {
            Files.createDirectory(dir);
            bridge.download(id, "input", dir.resolve("input.png"));
            bridge.download(id, "result", dir.resolve("result.png"));
        } catch (IOException e) {
            throw new InspectionFault(507, "LOCAL_STORAGE_FAILED", "后端结果图保存失败，请检查 INSPECTION_STORAGE_ROOT 目录。");
        }
        // Failure can leave unreferenced evidence; never delete shared data to recover it.
    }
    public Path file(UUID prediction, String variant) {
        if (!Set.of("input", "result").contains(variant)) throw new InspectionFault(404, "ARTIFACT_NOT_FOUND", "此类证据不向浏览器提供。");
        Path p = root.resolve(prediction.toString()).resolve(variant + ".png");
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !p.toRealPath().startsWith(root.toRealPath())) throw new IOException();
            return p;
        } catch (IOException e) { throw new InspectionFault(404, "ARTIFACT_NOT_FOUND", "结果图文件不存在，请检查后端证据目录和备份。"); }
    }
}
