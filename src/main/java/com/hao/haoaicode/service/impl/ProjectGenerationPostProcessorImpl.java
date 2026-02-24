package com.hao.haoaicode.service.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.monitor.AppMetricsCollector;
import com.hao.haoaicode.service.ProjectGenerationPostProcessor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
/**
 * 项目生成后处理服务实现类
 * 1. 解析多文件协议，提取 filePath -> fileContent 的映射。
 * 2. 缓存每个文件内容到内存（Caffeine Cache），用于预览。
 * 3. 上传每个文件到 COS 存储桶，更新 Redis 里的“最新源码路径”。
 * 4. 记录指标（如缓存命中率、上传耗时等）。
 */
@Service
public class ProjectGenerationPostProcessorImpl implements ProjectGenerationPostProcessor {

    @Resource
    CosManager cosManager;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Value("${code.source-cos-prefix:/source-code}")
    String sourceCosPrefix;
    @Resource
    AppMetricsCollector appMetricsCollector;
    @Resource
    MeterRegistry meterRegistry;
    // - 含义：记录每个 appId 当前在内存缓存里占用的体量快照（文件数、字符数）。
    // - 用途：当同一个 appId 再次生成（覆盖/增量合并）时，可以拿到旧快照 prev ，与新快照 next 做差量更新（ next - prev ），避免每次都全量遍历整个缓存来算总量。
    private final ConcurrentMap<Long, CacheStats> cacheStatsByAppId = new ConcurrentHashMap<>();
    private final LongAdder totalCachedFiles = new LongAdder();
    private final LongAdder totalCachedChars = new LongAdder();

    // 多文件协议相关标记
    private static final String FILE_MARKER_PREFIX = "<<<FILE:";
    private static final String FILE_MARKER_SUFFIX = ">>>";
    private static final String END_FILE_MARKER = "<<<END_FILE>>>";
    private static final String DONE_MARKER = "<<<DONE>>>";

    // 缓存：appId -> 所有生成的文件:（ filePath -> fileContent）
    private final Cache<Long, Map<String, String>> APP_ID_TO_FILES = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .recordStats()
            .removalListener((Long key, Map<String, String> value, RemovalCause cause) -> onCacheRemoval(key, value, cause))
            .build();

    @PostConstruct
    public void initMetrics() {
        CaffeineCacheMetrics.monitor(meterRegistry, APP_ID_TO_FILES, "app.codegen.generated_files_cache");

        Gauge.builder("app.codegen.cache.total_files", totalCachedFiles, LongAdder::sum)
                .register(meterRegistry);
        Gauge.builder("app.codegen.cache.total_chars", totalCachedChars, LongAdder::sum)
                .baseUnit("chars")
                .register(meterRegistry);
    }
    
    /**
     * 处理ai生成的Vue项目结果
     * 
     */
    @Override
    public ProjectGenerationResult processGeneration(long appId, String aiResponse) {
        Map<String, String> modelFiles = parseMultiFileProtocol(aiResponse);
        if (modelFiles.isEmpty()) {
            APP_ID_TO_FILES.invalidate(appId);
            appMetricsCollector.recordProjectGenerationResult("no_files");
            return new ProjectGenerationResult(false, false, null);
        }
        int generatedFileCount = modelFiles.size();
        long generatedChars = countChars(modelFiles);
        Map<String, String> mergedFiles = new LinkedHashMap<>();
        Map<String, String> previous = APP_ID_TO_FILES.getIfPresent(appId);

        if (previous != null && !previous.isEmpty()) {
            mergedFiles.putAll(previous);
        }
        mergedFiles.putAll(modelFiles);
        int mergedFileCount = mergedFiles.size();
        long mergedChars = countChars(mergedFiles);
        appMetricsCollector.recordCodeGenerationPayload(generatedFileCount, generatedChars, mergedFileCount, mergedChars);

        updateCacheStatsOnPut(appId, mergedFiles);
        APP_ID_TO_FILES.put(appId, Collections.unmodifiableMap(mergedFiles));
        String baseKey = buildSourceBaseKey(appId);
        boolean uploaded = uploadFilesToCos(baseKey, appId, mergedFiles);
        if (uploaded) {
            appMetricsCollector.recordProjectGenerationResult("upload_success");
        } else {
            appMetricsCollector.recordProjectGenerationResult("upload_failed");
        }
        String normalizedBaseKey = uploaded ? ensureDirKey(baseKey) : null;
        return new ProjectGenerationResult(true, uploaded, normalizedBaseKey);
    }

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
        int doneIndex = text.lastIndexOf(DONE_MARKER);//获取结束标记的位置
        String input = doneIndex >= 0 ? text.substring(0, doneIndex) : text; // 截取结束标记之前的内容

        // 使用 LinkedHashMap 保证文件插入顺序
        Map<String, String> files = new LinkedHashMap<>();
        int idx = 0;

        // 循环解析每个文件块，直到没有更多的 FILE_MARKER_PREFIX 出现
        while (true) {
            // 查找文件块起始标记，从索引idx开始查找
            int start = input.indexOf(FILE_MARKER_PREFIX, idx);//指定起始位置查找字符串
            if (start < 0) {
                break;
            }

            // 找到路径起始的索引
            int pathStart = start + FILE_MARKER_PREFIX.length();
            // 找到相对路径结束的索引
            int pathEnd = input.indexOf(FILE_MARKER_SUFFIX, pathStart);
            if (pathEnd < 0) {
                // 缺少路径结束标记，认为协议格式异常
                break;
            }
            // 把相对路径截取出来，示例：<<<FILE:src/main/java/
            String rawPath = input.substring(pathStart, pathEnd).trim();
            // 处理相对路径，去除首尾空格和路径分隔符。解析出干净的相对路径
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
            // 对截取的文件内容做清洗，去掉末尾多余的换行符
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            } else if (content.endsWith("\n")) {
                content = content.substring(0, content.length() - 1);
            }

            // 把清洗好的相对路径和对应的内容写入 Map
            if (relativePath != null) {
                files.put(relativePath, content);
            }

            // 将扫描指针移动到本次 END_FILE_MARKER 之后，准备解析下一个文件块
            idx = end + END_FILE_MARKER.length();
        }

        return files;
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
        String baseKey = buildSourceBaseKey(appId);
        return uploadFilesToCos(baseKey, appId, files);
    }


    /**
     * 上传文件到 COS
     * @param baseKey
     * @param appId
     * @param files
     * @return
     */
    private boolean uploadFilesToCos(String baseKey, long appId, Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return false;
        }
        long startNs = System.nanoTime();
        boolean uploaded = false;
        try {
            uploaded = cosManager.uploadTextFiles(baseKey, files);
            return uploaded;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            appMetricsCollector.recordCosUpload(uploaded ? "success" : "failed", durationMs);
            if (uploaded) {
                String normalizedBaseKey = ensureDirKey(baseKey);
                stringRedisTemplate.opsForValue()
                        .set(String.format("code:source:latest:%d", appId), normalizedBaseKey);
            }
        }
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
     * 构建 COS 存储路径的基础目录,示例：source-code/1694582400000（appid）128000（当前时间戳）
     * @param appId
     * @return
     */
    private String buildSourceBaseKey(long appId) {
        String prefix = (sourceCosPrefix == null || sourceCosPrefix.isBlank()) ? "/source-code" : sourceCosPrefix.trim();
        // 替换 Windows 风格的路径分隔符为 '/'
        prefix = prefix.replace('\\', '/');
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            // 把结尾的 '/' 去掉
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
    private record CacheStats(int fileCount, long charCount) {
    }

    private void updateCacheStatsOnPut(long appId, Map<String, String> mergedFiles) {
        CacheStats next = new CacheStats(mergedFiles != null ? mergedFiles.size() : 0, countChars(mergedFiles));
        CacheStats prev = cacheStatsByAppId.put(appId, next);
        if (prev == null) {
            totalCachedFiles.add(next.fileCount());
            totalCachedChars.add(next.charCount());
            return;
        }
        totalCachedFiles.add((long) next.fileCount() - prev.fileCount());
        totalCachedChars.add(next.charCount() - prev.charCount());
    }

    private void onCacheRemoval(Long appId, Map<String, String> value, RemovalCause cause) {
        if (appId == null) {
            return;
        }
        CacheStats removed = cacheStatsByAppId.remove(appId);
        if (removed != null) {
            totalCachedFiles.add(-removed.fileCount());
            totalCachedChars.add(-removed.charCount());
            return;
        }
        if (value != null) {
            totalCachedFiles.add(-value.size());
            totalCachedChars.add(-countChars(value));
        }
    }

    private static long countChars(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (String content : files.values()) {
            if (content != null) {
                sum += content.length();
            }
        }
        return sum;
    }
    /**
     * 确保 COS 存储路径以 '/' 结尾，格式清洗
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
