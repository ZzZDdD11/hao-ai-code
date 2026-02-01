package com.hao.haoaicode.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 批量文件写入工具
 * 允许 AI 一次性写入多个文件，后端使用线程池并发处理
 */
@Slf4j
@Component
public class BatchFileWriteTool extends BaseTool {

    @Resource(name = "taskExecutor")
    private Executor taskExecutor;

    @Override
    public String getToolName() {
        return "writeBatchFiles";
    }

    @Override
    public String getDisplayName() {
        return "批量写入多个文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        // 简化的展示，避免日志过长
        try {
            Object filesObj = arguments.get("files");
            int size = 0;
            if (filesObj instanceof JSONArray) {
                size = ((JSONArray) filesObj).size();
            } else if (filesObj instanceof List) {
                size = ((List<?>) filesObj).size();
            }
            return String.format("[工具调用] 批量写入 %d 个文件", size);
        } catch (Exception e) {
            return "[工具调用] 批量写入文件";
        }
    }

    @Data
    public static class FileEntry {

        private String relativeFilePath;


        private String content;
    }

    @Tool("批量写入多个文件到指定路径")
    public String writeBatchFiles(
            @P("文件列表")
            List<FileEntry> files,
            @ToolMemoryId String sessionId
    ) {
        try {
            Long appId = extractAppIdFromSessionId(sessionId);
            
            if (files == null || files.isEmpty()) {
                return "没有文件需要写入";
            }

            List<CompletableFuture<String>> futures = new ArrayList<>();
            List<String> successFiles = new ArrayList<>();
            List<String> failedFiles = new ArrayList<>();

            for (FileEntry fileEntry : files) {
                String relativeFilePath = fileEntry.getRelativeFilePath();
                String content = fileEntry.getContent();

                if (relativeFilePath == null || content == null) {
                    continue;
                }

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        Path path = Paths.get(relativeFilePath);
                        if (!path.isAbsolute()) {
                            String projectDirName = "vue_project_" + appId;
                            Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                            path = projectRoot.resolve(relativeFilePath);
                        }
                        
                        Path parentDir = path.getParent();
                        if (parentDir != null) {
                            Files.createDirectories(parentDir);
                        }
                        
                        Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                        
                        return relativeFilePath;
                    } catch (Exception e) {
                        log.error("批量写入单个文件失败: {}", relativeFilePath, e);
                        throw new RuntimeException("Write failed: " + relativeFilePath);
                    }
                }, taskExecutor).handle((res, ex) -> {
                    if (ex == null) {
                        return "SUCCESS:" + res;
                    } else {
                        return "FAIL:" + relativeFilePath + " (" + ex.getMessage() + ")";
                    }
                });
                
                futures.add(future);
            }

            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集结果
            for (CompletableFuture<String> future : futures) {
                String result = future.get(); // 已经join过了，这里直接get
                if (result.startsWith("SUCCESS:")) {
                    successFiles.add(result.substring(8));
                } else {
                    failedFiles.add(result.substring(5));
                }
            }

            StringBuilder resultMsg = new StringBuilder();
            resultMsg.append("批量写入完成。");
            if (!successFiles.isEmpty()) {
                resultMsg.append("\n成功写入: ").append(String.join(", ", successFiles));
            }
            if (!failedFiles.isEmpty()) {
                resultMsg.append("\n写入失败: ").append(String.join(", ", failedFiles));
            }
            
            return resultMsg.toString();

        } catch (Exception e) {
            log.error("批量写入文件全局失败", e);
            return "批量写入失败: " + e.getMessage();
        }
    }

    private Long extractAppIdFromSessionId(String sessionId) {
        if (sessionId == null || !sessionId.contains(":")) {
            throw new IllegalArgumentException("Invalid sessionId format, expected userId:appId");
        }
        String[] parts = sessionId.split(":");
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid appId in sessionId: " + sessionId);
        }
    }
}
