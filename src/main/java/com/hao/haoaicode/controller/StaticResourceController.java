package com.hao.haoaicode.controller;


import com.hao.haoaicode.config.CosClientConfig;
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

import static com.hao.haoaicode.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

@RestController
@RequestMapping("/static")
public class StaticResourceController {

    // 应用生成根目录（用于浏览）
    private static final String PREVIEW_ROOT_DIR = CODE_OUTPUT_ROOT_DIR;

    @Value("${code.deploy-cos-prefix:/deploy}")
    private String deployCosPrefix;

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:8123/api/static/{deployKey}[/{fileName}]
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<org.springframework.core.io.Resource> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request) {
        try {
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
     */
    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private String buildCosObjectKey(String deployKey, String resourcePath) {
        String prefix = normalizeDirKey(deployCosPrefix) + (deployKey == null ? "" : deployKey.trim()) + "/";
        String path = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return prefix + path;
    }

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
