package com.hao.haoaicode.controller;


import com.hao.haoaicode.config.CosClientConfig;
import com.hao.haoaicode.service.ProjectGenerationPostProcessor;
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


    // 部署到 COS 时使用的对象存储前缀，例如：/deploy
    @Value("${code.deploy-cos-prefix:/deploy}")
    private String deployCosPrefix;

    // COS 客户端配置（主要用来获取 bucket 等信息）
    @Resource
    private CosClientConfig cosClientConfig;

    // 腾讯云 COS 客户端，用于从对象存储中读取静态资源
    @Resource
    private COSClient cosClient;

    @Resource
    private ProjectGenerationPostProcessor projectGenerationPostProcessor;

    /**
     * 静态资源访问入口（仅支持已部署站点）。
     * <p>
     * 路径模式：/static/{deployKey}/**
     * <ul>
     *   <li>deployKey 为部署标识（App.deployKey），对应 COS 中 /{deployCosPrefix}/{deployKey} 目录；</li>
     *   <li>不再支持基于 codeGenType_appId 的本地 / 内存预览，预览请通过部署后的地址访问。</li>
     * </ul>
     * 访问流程：
     * <ol>
     *   <li>从请求中解析出相对资源路径 resourcePath；</li>
     *   <li>若访问目录（末尾无文件名），重定向到加 / 的路径；</li>
     *   <li>将 resourcePath 归一化，并拼接成 COS 对象 Key；</li>
     *   <li>从 COS 读取对象流并返回给前端。</li>
     * </ol>
     */
    /**
     * 
     * @param deployKey
     * @param request
     * @return 返回一个 HTTP 响应对象，body 是一个 Spring 的 Resource（也就是一个可读流，比如文件内容）
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<org.springframework.core.io.Resource> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request) {
        try {
            // 从 HandlerMapping 中获取完整路径，再去掉 /static/{deployKey} 前缀，得到相对资源路径
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            if ("/".equals(resourcePath)) {
                resourcePath = "/index.html";
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
                // COS 返回 404 时，转换为 HTTP 404
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
