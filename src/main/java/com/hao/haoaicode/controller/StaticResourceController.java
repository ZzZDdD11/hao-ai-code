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
            // 从请求属性中拿到原始匹配路径，例如：/static/{deployKey}/index.html
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            // 去掉 /static/{deployKey} 前缀，只保留后面的相对路径部分
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());
            // 如果只访问到目录（没有带结尾的 /），做一次 301 重定向，补上 /
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            // 如果访问的是目录（以 / 结尾），默认返回 index.html
            if (resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            // 先尝试从本地预览目录读取文件：{CODE_OUTPUT_ROOT_DIR}/{deployKey}/{resourcePath}
            String filePath = PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            if (file.exists()) {
                org.springframework.core.io.Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                        // 根据文件扩展名设置合适的 Content-Type
                        .header("Content-Type", getContentTypeWithCharset(filePath))
                        .body(resource);
            }

            // 本地没有对应文件时，回源到 COS 获取对象内容
            String objectKey = buildCosObjectKey(deployKey, resourcePath);
            COSObject cosObject = cosClient.getObject(cosClientConfig.getBucket(), objectKey);
            ObjectMetadata metadata = cosObject.getObjectMetadata();
            InputStreamResource resource = new InputStreamResource(cosObject.getObjectContent());
            HttpHeaders headers = new HttpHeaders();
            // 优先使用 COS 存储时记录的 Content-Type，没有则根据后缀推断
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
            // COS 返回 404 时，转换为 HTTP 404
            if (e.getStatusCode() == 404) {
                return ResponseEntity.notFound().build();
            }
            // 其它 COS 异常返回 500
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            // 兜底异常处理，避免异常栈直接抛到前端
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
