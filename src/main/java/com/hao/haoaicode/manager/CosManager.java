package com.hao.haoaicode.manager;

import com.hao.haoaicode.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * COS对象存储管理器
 *
 * @author 
 */
@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        String objectKey = normalizeObjectKey(key);
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), objectKey, file);
        return cosClient.putObject(putObjectRequest);
    }

    public PutObjectResult putObject(String key, File file, String contentType) {
        String objectKey = normalizeObjectKey(key);
        com.qcloud.cos.model.ObjectMetadata metadata = createMetadata(file == null ? 0 : file.length(), objectKey, contentType);
        PutObjectRequest putObjectRequest;
        if (file == null) {
            putObjectRequest = new PutObjectRequest(
                    cosClientConfig.getBucket(),
                    objectKey,
                    new java.io.ByteArrayInputStream(new byte[0]),
                    metadata
            );
        } else {
            putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), objectKey, file);
            putObjectRequest.setMetadata(metadata);
        }
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键（完整路径）
     * @param file 要上传的文件
     * @return 文件的访问URL，失败返回null
     */
    public String uploadFile(String key, File file) {
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            String url = buildFileUrl(key);
            log.info("文件上传COS成功: {} -> {}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败，返回结果为空");
            return null;
        }
    }

    public boolean uploadFileWithContentType(String key, File file, String contentType) {
        String objectKey = normalizeObjectKey(key);
        try {
            PutObjectResult result = putObject(objectKey, file, contentType);
            return result != null;
        } catch (Exception e) {
            log.error("上传文件到COS失败: {}, 错误: {}", objectKey, e.getMessage(), e);
            return false;
        }
    }

    public boolean uploadDirectory(String baseKey, File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            log.warn("上传目录到COS失败：目录不存在或不是目录，baseKey: {}, dir: {}", baseKey, directory == null ? null : directory.getAbsolutePath());
            return false;
        }
        String normalizedBaseKey = normalizeKey(baseKey);
        Path root = directory.toPath();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relPath = root.relativize(path);
                String relative = relPath.toString().replace('\\', '/');
                String objectKey = normalizedBaseKey + relative;
                boolean ok = uploadFileWithContentType(objectKey, path.toFile(), guessContentType(relative));
                if (!ok) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            log.error("遍历目录失败: {}, error: {}", directory.getAbsolutePath(), e.getMessage(), e);
            return false;
        }
    }


    /**
     * 上传字符串内容到 COS
     * 用于大消息分层存储
     *
     * @param key     COS对象键（完整路径）
     * @param content 要上传的字符串内容
     * @return 上传成功返回 key，失败返回 null
     */
    public String uploadContent(String key, String content) {
        try {
            String objectKey = normalizeObjectKey(key);
            byte[] bytes = (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.qcloud.cos.model.ObjectMetadata metadata = createMetadata(bytes.length, objectKey, "application/json; charset=utf-8");

            PutObjectRequest request = new PutObjectRequest(
                    cosClientConfig.getBucket(),
                    objectKey,
                    new java.io.ByteArrayInputStream(bytes),
                    metadata
            );
            cosClient.putObject(request);

            log.info("内容上传COS成功: {}, 大小: {} bytes", objectKey, bytes.length);
            return objectKey;
        } catch (Exception e) {
            log.error("内容上传COS失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 COS 下载字符串内容
     * 用于记忆恢复时加载大消息
     *
     * @param key COS对象键（完整路径）
     * @return 下载的字符串内容，失败返回 null
     */
    public String downloadContent(String key) {
        try {
            String objectKey = normalizeObjectKey(key);
            com.qcloud.cos.model.COSObject cosObject = cosClient.getObject(cosClientConfig.getBucket(), objectKey);
            try (java.io.InputStream inputStream = cosObject.getObjectContent()) {
                String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("内容下载COS成功: {}, 大小: {} bytes", objectKey, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("内容下载COS失败: {}, 错误: {}", key, e.getMessage(), e);
            return null;
        }
    }

    public String buildFileUrl(String key) {
        return buildFileUrlWithHost(cosClientConfig.getHost(), key);
    }

    public String buildHtmlUrl(String key) {
        String websiteHost = resolveWebsiteHost(cosClientConfig.getHost());
        return buildFileUrlWithHost(websiteHost, key);
    }

    private String buildFileUrlWithHost(String host, String key) {
        String h = host == null ? "" : host.trim();
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        String k = key == null ? "" : key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return h + "/" + k;
    }

    private String resolveWebsiteHost(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        String h = host.trim();
        if (h.contains(".cos-website.")) {
            return h;
        }
        int schemeIndex = h.indexOf("://");
        String scheme = "";
        String rest = h;
        if (schemeIndex > 0) {
            scheme = h.substring(0, schemeIndex + 3);
            rest = h.substring(schemeIndex + 3);
        }
        String replaced = rest.replace(".cos.", ".cos-website.");
        return scheme + replaced;
    }

    public boolean uploadTextFile(String key, String content, String contentType) {
        String objectKey = normalizeObjectKey(key);
        try {
            byte[] bytes = content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String finalContentType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
            com.qcloud.cos.model.ObjectMetadata metadata = createMetadata(bytes.length, objectKey, finalContentType);

            PutObjectRequest request = new PutObjectRequest(
                    cosClientConfig.getBucket(),
                    objectKey,
                    new java.io.ByteArrayInputStream(bytes),
                    metadata
            );
            cosClient.putObject(request);
            return true;
        } catch (Exception e) {
            log.error("上传文本文件到COS失败: {}, 错误: {}", objectKey, e.getMessage(), e);
            return false;
        }
    }

    public boolean uploadTextFiles(String baseKey, java.util.Map<String, String> relativePathToContent) {
        if (relativePathToContent == null || relativePathToContent.isEmpty()) {
            log.warn("上传源码到COS：文件集合为空，baseKey: {}", baseKey);
            return false;
        }
        String normalizedBaseKey = normalizeKey(baseKey);
        for (java.util.Map.Entry<String, String> entry : relativePathToContent.entrySet()) {
            String relativePath = normalizeRelativePath(entry.getKey());
            if (relativePath == null) {
                continue;
            }
            String objectKey = normalizedBaseKey + relativePath;
            boolean ok = uploadTextFile(objectKey, entry.getValue(), guessContentType(relativePath));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String normalizeObjectKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return k;
    }

    private com.qcloud.cos.model.ObjectMetadata createMetadata(long contentLength, String key, String contentType) {
        com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
        metadata.setContentLength(Math.max(0, contentLength));
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        metadata.setContentDisposition("inline");
        return metadata;
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        if (!k.endsWith("/")) {
            k = k + "/";
        }
        return k;
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String p = relativePath.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private String guessContentType(String fileKey) {
        String lower = fileKey == null ? "" : fileKey.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".map")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".ts")) return "application/typescript; charset=UTF-8";
        if (lower.endsWith(".vue")) return "text/plain; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

}
