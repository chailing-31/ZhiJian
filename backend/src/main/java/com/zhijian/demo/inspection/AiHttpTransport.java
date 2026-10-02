package com.zhijian.demo.inspection;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** JDK-only, bounded HTTP transport. No redirects or URLs taken from model output. */
public final class AiHttpTransport {
    public record Reply(int status, String contentType, byte[] body) {}
    private final URI base;
    private final int readTimeout;
    public AiHttpTransport(String baseUrl, int readTimeout) {
        base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
            || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || (base.getPath() != null && !base.getPath().isEmpty()))
            throw new IllegalArgumentException("AI_SERVICE_BASE_URL must be an http(s) origin without a path.");
        this.readTimeout = readTimeout;
    }
    private HttpURLConnection connection(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) base.resolve(path).toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(5000); c.setReadTimeout(readTimeout); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }
    public Reply ready() throws IOException { return request("/ready", null, null); }
    public Reply predict(long batchId, String code, byte[] image, String mime) throws IOException {
        if (!code.matches("[A-Za-z0-9-]{1,64}") || batchId < 1) throw new IllegalArgumentException("Invalid batch.");
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(mime)) throw new IllegalArgumentException("Invalid MIME.");
        String boundary = "ZhiJian" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "batch_id", String.valueOf(batchId));
        part(body, boundary, "batch_code", code);
        String ext = mime.equals("image/jpeg") ? "jpg" : mime.substring(6);
        ascii(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"upload." + ext
            + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
        body.write(image); ascii(body, "\r\n--" + boundary + "--\r\n");
        return request("/ai/inspection/predict", body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }
    private static void part(OutputStream b, String boundary, String name, String value) throws IOException {
        ascii(b, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void ascii(OutputStream b, String s) throws IOException { b.write(s.getBytes(StandardCharsets.UTF_8)); }
    private Reply request(String path, byte[] body, String type) throws IOException {
        HttpURLConnection c = connection(path);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            int status = c.getResponseCode();
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] bytes;
            if (stream == null) bytes = new byte[0];
            else try (stream) { bytes = stream.readNBytes(2 * 1024 * 1024 + 1); }
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("AI JSON response exceeds limit.");
            return new Reply(status, c.getContentType(), bytes);
        } finally { c.disconnect(); }
    }
    public void downloadPng(UUID prediction, String variant, Path output) throws IOException {
        if (!Set.of("input", "result").contains(variant)) throw new IllegalArgumentException("Invalid artifact variant.");
        HttpURLConnection c = connection("/internal/artifacts/" + prediction + "/" + variant);
        c.setRequestProperty("Accept", "image/png");
        try {
            if (c.getResponseCode() != 200 || c.getContentType() == null
                || !c.getContentType().toLowerCase(Locale.ROOT).startsWith("image/png"))
                throw new IOException("AI artifact is unavailable or not PNG.");
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                byte[] buf = new byte[65536]; long total = 0; int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 80L * 1024 * 1024) throw new IOException("AI artifact exceeds limit.");
                    out.write(buf, 0, n);
                }
            }
            try (InputStream in = Files.newInputStream(output)) {
                if (!Arrays.equals(in.readNBytes(8), new byte[]{(byte)137,80,78,71,13,10,26,10}))
                    throw new IOException("Invalid PNG signature.");
            }
        } catch (IOException e) { Files.deleteIfExists(output); throw e; }
        finally { c.disconnect(); }
    }
}
