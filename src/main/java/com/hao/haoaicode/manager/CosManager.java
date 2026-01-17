package com.hao.haoaicode.manager;

import com.hao.haoaicode.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * COS对象存储管理器
 *
 * @author yupi
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
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
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
        // 上传文件
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            // 构建访问URL
            String url = String.format("%s%s", cosClientConfig.getHost(), key);
            log.info("文件上传COS成功: {} -> {}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败，返回结果为空");
            return null;
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
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType("application/json; charset=utf-8");

            PutObjectRequest request = new PutObjectRequest(
                    cosClientConfig.getBucket(),
                    key,
                    new java.io.ByteArrayInputStream(bytes),
                    metadata
            );
            cosClient.putObject(request);

            log.info("内容上传COS成功: {}, 大小: {} bytes", key, bytes.length);
            return key;
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
            com.qcloud.cos.model.COSObject cosObject = cosClient.getObject(cosClientConfig.getBucket(), key);
            try (java.io.InputStream inputStream = cosObject.getObjectContent()) {
                String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("内容下载COS成功: {}, 大小: {} bytes", key, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("内容下载COS失败: {}, 错误: {}", key, e.getMessage(), e);
            return null;
        }
    }

}
