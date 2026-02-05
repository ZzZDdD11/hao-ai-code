package com.hao.haoaicode.controller;


import com.hao.haoaicode.config.CosClientConfig;
import com.hao.haoaicode.core.handler.JsonMessageStreamHandler;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.hao.haoaicode.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

@RestController
@RequestMapping("/static")
public class StaticResourceController {

    // 应用生成根目录（用于浏览本地构建后的静态资源）
    private static final String PREVIEW_ROOT_DIR = CODE_OUTPUT_ROOT_DIR;

    // 部署到 COS 时使用的对象存储前缀，例如：/deploy
    @Value("${code.deploy-cos-prefix:/deploy}")
    private String deployCosPrefix;

    // COS 客户端配置（主要用来获取 bucket 等信息）
    @Resource
    private CosClientConfig cosClientConfig;

    // 腾讯云 COS 客户端，用于从对象存储中读取静态资源
    @Resource
    private COSClient cosClient;

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:8123/api/static/{deployKey}[/{fileName}]
     *
     * 说明：
     * - deployKey 一般对应某次部署生成的静态网站目录名
     * - 优先从本地 PREVIEW_ROOT_DIR 查找（便于本地调试 / 预览）
     * - 如果本地不存在，则回源到 COS 上的对应对象
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<org.springframework.core.io.Resource> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request) {
        try {
            // 预览模式：deployKey 形如 codeGenType_appId（例如 VUE_PROJECT_123）
            int underscore = deployKey.lastIndexOf('_');
            if (underscore > 0 && underscore < deployKey.length() - 1) {
                String idPart = deployKey.substring(underscore + 1);
                try {
                    long appId = Long.parseLong(idPart);
                    String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
                    resourcePath = resourcePath.substring(("/static/" + deployKey).length());
                    if (resourcePath.isEmpty()) {
                        HttpHeaders headers = new HttpHeaders();
                        headers.add("Location", request.getRequestURI() + "/");
                        return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
                    }
                    String path = resourcePath.replace('\\', '/');
                    while (path.startsWith("/")) {
                        path = path.substring(1);
                    }
                    Map<String, String> files = JsonMessageStreamHandler.getGeneratedFiles(appId);
                    if (!files.isEmpty()) {
                        String key = path;
                        if (key.isEmpty() || "/".equals(key)) {
                            key = "index.html";
                        }
                        String content = files.get(key);
                        // 兼容前端可能访问 dist/index.html，而模型通常生成根目录 index.html
                        if (content == null && key.startsWith("dist/")) {
                            String fallbackKey = key.substring("dist/".length());
                            content = files.get(fallbackKey);
                            if (content != null) {
                                key = fallbackKey;
                            }
                        } else if (content == null && "index.html".equals(key)) {
                            // 反向兼容：如果模型生成 dist/index.html，而前端访问 index.html
                            String fallbackKey = "dist/index.html";
                            content = files.get(fallbackKey);
                            if (content != null) {
                                key = fallbackKey;
                            }
                        }
                        if (content != null) {
                            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                            InputStreamResource resource = new InputStreamResource(new java.io.ByteArrayInputStream(bytes));
                            HttpHeaders headers = new HttpHeaders();
                            headers.add("Content-Type", getContentTypeWithCharset(key));
                            headers.setContentLength(bytes.length);
                            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
                        }
                    }
                    // 若内存中未命中，则继续走本地 / COS 逻辑
                } catch (NumberFormatException ignored) {
                }
            }

            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            if (resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            String filePath = PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            if (file.exists()) {
                org.springframework.core.io.Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                        .header("Content-Type", getContentTypeWithCharset(filePath))
                        .body(resource);
            }

            String objectKey = buildCosObjectKey(deployKey, resourcePath);
            COSObject cosObject = cosClient.getObject(cosClientConfig.getBucket(), objectKey);
            ObjectMetadata metadata = cosObject.getObjectMetadata();
            InputStreamResource resource = new InputStreamResource(cosObject.getObjectContent());
            HttpHeaders headers = new HttpHeaders();
            String contentType = metadata == null ? null : metadata.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = getContentTypeWithCharset(objectKey);
            }
            headers.add("Content-Type", contentType);
            long contentLength = metadata == null ? -1 : metadata.getContentLength();
            if (contentLength >= 0) {
                headers.setContentLength(contentLength);
            }
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (CosServiceException e) {
            if (e.getStatusCode() == 404) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 根据文件扩展名返回带字符编码的 Content-Type
     * 前端 HTML / CSS / JS 统一使用 UTF-8 编码，图片保持二进制类型
     */
    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }

    /**
     * 构造 COS 中静态资源的对象 Key
     * 最终形式大致为：{deployCosPrefix}/{deployKey}/{resourcePath}
     */
    private String buildCosObjectKey(String deployKey, String resourcePath) {
        String prefix = normalizeDirKey(deployCosPrefix) + (deployKey == null ? "" : deployKey.trim()) + "/";
        // 统一使用 / 作为路径分隔符，去掉开头多余的 /
        String path = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return prefix + path;
    }

    /**
     * 规范化目录前缀，去掉首尾多余的 /，并确保以 / 结尾
     */
    private String normalizeDirKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        while (k.endsWith("/")) {
            k = k.substring(0, k.length() - 1);
        }
        return k + "/";
    }
}
