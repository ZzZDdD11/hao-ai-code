package com.hao.haoaicode.service.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.monitor.AppMetricsCollector;
import com.hao.haoaicode.service.ProjectGenerationPostProcessor;

import jakarta.annotation.Resource;

@Service
public class ProjectGenerationPostProcessorImpl implements ProjectGenerationPostProcessor {

    @Resource
    CosManager cosManager;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Value("${code.deploy-cos-prefix:/source-code}")
    String sourceCosPrefix;
    @Resource
    AppMetricsCollector appMetricsCollector;

    // 多文件协议相关标记
    private static final String FILE_MARKER_PREFIX = "<<<FILE:";
    private static final String FILE_MARKER_SUFFIX = ">>>";
    private static final String END_FILE_MARKER = "<<<END_FILE>>>";
    private static final String DONE_MARKER = "<<<DONE>>>";

    private final Cache<Long,Map<String,String>> APP_ID_TO_FILES = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();
    /**
     * 解析多文件，返回文件路径到文件内容的映射
     */
    @Override
    public Map<String, String> parseMultiFileProtocol(String text) {
        // 判空：没有任何内容时，直接返回空 Map
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }

        // DONE_MARKER 作为整体结束标记，只取它之前的内容参与解析
        int doneIndex = text.lastIndexOf(DONE_MARKER);
        String input = doneIndex >= 0 ? text.substring(0, doneIndex) : text;

        // 使用 LinkedHashMap 保证文件插入顺序
        Map<String, String> files = new LinkedHashMap<>();
        int idx = 0;

        while (true) {
            // 查找下一个文件块起始标记
            int start = input.indexOf(FILE_MARKER_PREFIX, idx);
            if (start < 0) {
                break;
            }

            // 提取路径
            int pathStart = start + FILE_MARKER_PREFIX.length();
            int pathEnd = input.indexOf(FILE_MARKER_SUFFIX, pathStart);
            if (pathEnd < 0) {
                // 缺少路径结束标记，认为协议格式异常
                break;
            }
            String rawPath = input.substring(pathStart, pathEnd).trim();
            String relativePath = normalizeRelativePath(rawPath);

            // 文件内容起点：紧随路径结束标记之后，跳过紧邻的 \r 和 \n
            int contentStart = pathEnd + FILE_MARKER_SUFFIX.length();
            if (contentStart < input.length() && input.charAt(contentStart) == '\r') {
                contentStart++;
            }
            if (contentStart < input.length() && input.charAt(contentStart) == '\n') {
                contentStart++;
            }

            // 查找当前文件块的结束标记
            int end = input.indexOf(END_FILE_MARKER, contentStart);
            if (end < 0) {
                // 未找到结束标记，认为协议格式已损坏
                break;
            }

            // 截取文件内容（不包含 END_FILE_MARKER 本身）
            String content = input.substring(contentStart, end);
            // 去掉末尾多余的换行符
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            } else if (content.endsWith("\n")) {
                content = content.substring(0, content.length() - 1);
            }

            // 只有在路径合法时才写入 Map
            if (relativePath != null) {
                files.put(relativePath, content);
            }

            // 将扫描指针移动到本次 END_FILE_MARKER 之后
            idx = end + END_FILE_MARKER.length();
        }

        return files;
    }

    @Override
    public ProjectGenerationResult processGeneration(long appId, String aiResponse) {
        // 1. 解析多文件协议
        Map<String, String> modelFiles = parseMultiFileProtocol(aiResponse);
        if (modelFiles.isEmpty()) {
            // 未解析出任何文件：清理缓存，并返回“无文件”
            APP_ID_TO_FILES.invalidate(appId);
            // 进行监控指标收集
            appMetricsCollector.recordProjectGenerationResult("no_files");
            return new ProjectGenerationResult(false, false);
        }

        // 2. 合并本次生成的文件与历史缓存，支持增量更新
        Map<String, String> mergedFiles = new LinkedHashMap<>();
        Map<String, String> previous = APP_ID_TO_FILES.getIfPresent(appId);
        if (previous != null && !previous.isEmpty()) {
            mergedFiles.putAll(previous);
        }
        mergedFiles.putAll(modelFiles);

        // 3. 写入内存缓存（只读视图，用于预览）
        APP_ID_TO_FILES.put(appId, Collections.unmodifiableMap(mergedFiles));

        // 4. 上传到 COS，并更新 Redis 中的最新源码目录
        boolean uploaded = uploadFilesToCos(appId, mergedFiles);
        // 进行监控指标收集
        if(uploaded){
        appMetricsCollector.recordProjectGenerationResult("upload_success");
        }else{
            appMetricsCollector.recordProjectGenerationResult("upload_failed");
        }
        return new ProjectGenerationResult(true, uploaded);
    }

    @Override
    public Map<String, String> getGeneratedFiles(long appId) {
        Map<String, String> files = APP_ID_TO_FILES.getIfPresent(appId);
        return files != null ? files : Collections.emptyMap();
    }

    @Override
    public String getGeneratedFileContent(long appId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Map<String, String> files = APP_ID_TO_FILES.getIfPresent(appId);
        if (files == null || files.isEmpty()) {
            return null;
        }
        String normalizedPath = normalizeRelativePath(filePath);
        if (normalizedPath == null) {
            return null;
        }
        return files.get(normalizedPath);
    }

    @Override
    public void clearGeneratedFiles(long appId) {
        APP_ID_TO_FILES.invalidate(appId);
    }

    @Override
    public boolean uploadToCos(long appId) {
        Map<String, String> files = APP_ID_TO_FILES.getIfPresent(appId);
        if (files == null || files.isEmpty()) {
            return false;
        }
        return uploadFilesToCos(appId, files);
    }


    /**
     * 上传文件到 COS
     * @param appId
     * @param files
     * @return
     */
    private boolean uploadFilesToCos(long appId, Map<String, String> files) {
    if (files == null || files.isEmpty()) {
        return false;
    }
    // 构建 COS 存储路径的基础目录
    String baseKey = buildSourceBaseKey(appId);
    boolean uploaded = cosManager.uploadTextFiles(baseKey, files);
    if (!uploaded) {
        return false;
    }

    // 上传成功后，写 Redis 标记最新源码目录
    String normalizedBaseKey = ensureDirKey(baseKey);
    stringRedisTemplate.opsForValue()
            .set(String.format("code:source:latest:%d", appId), normalizedBaseKey);

    return true;
    }
    /**
     * 归一化相对路径，去掉前导 '/' 并检查是否合法
     * @param relativePath
     * @return
     */
    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String p = relativePath.replace('\\', '/').trim();
        // 去掉前导 '/'
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isBlank()) {
            return null;
        }
        // 禁止目录穿越和非法字符
        if (p.contains("..") || p.contains(":") || p.contains("\u0000")) {
            return null;
        }
        return p;
    }
    /**
     * 构建 COS 存储路径的基础目录
     * @param appId
     * @return
     */
    private String buildSourceBaseKey(long appId) {
        String prefix = (sourceCosPrefix == null || sourceCosPrefix.isBlank())
                ? "/source-code"
                : sourceCosPrefix.trim();

        prefix = prefix.replace('\\', '/');
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        // 这里用时间戳作为一个版本号，避免覆盖历史源码
        return String.format("%s/%d/%d", prefix, appId, System.currentTimeMillis());
    }
    /**
     * 确保 COS 存储路径以 '/' 结尾
     * @param key
     * @return
     */
    private String ensureDirKey(String key) {
        if (key == null || key.isBlank()) {
            return "/";
        }
        String k = key.replace('\\', '/');
        if (!k.startsWith("/")) {
            k = "/" + k;
        }
        if (!k.endsWith("/")) {
            k = k + "/";
        }
        return k;
    }
    
}
