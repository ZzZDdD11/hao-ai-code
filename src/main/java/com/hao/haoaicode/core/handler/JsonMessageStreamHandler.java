package com.hao.haoaicode.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.model.message.ToolExecutedMessage;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应
 * 职责：
 * 1. 将 TokenStream 转换为前端可用的 JSON 字符串流
 * 2. 收集对话历史
 * 3. 触发 Vue 项目构建
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ChatMessageRouter chatMessageRouter;

    @Resource
    private CosManager cosManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 源码的前缀
    @Value("${code.source-cos-prefix:/source-code}")
    private String sourceCosPrefix;

    private static final String FILE_MARKER_PREFIX = "<<<FILE:";
    private static final String FILE_MARKER_SUFFIX = ">>>";
    private static final String END_FILE_MARKER = "<<<END_FILE>>>";
    private static final String DONE_MARKER = "<<<DONE>>>";
    // 应用ID到生成的文件的映射
    private static final ConcurrentHashMap<Long, Map<String, String>> APP_ID_TO_FILES = new ConcurrentHashMap<>();


    public static Map<String, String> getGeneratedFiles(long appId) {
        return APP_ID_TO_FILES.getOrDefault(appId, Collections.emptyMap());
    }

    public static void clearGeneratedFiles(long appId) {
        APP_ID_TO_FILES.remove(appId);
    }

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 直接消费 TokenStream，避免了反复序列化/反序列化 JSON 的开销
     *
     * @param tokenStream 原始 TokenStream
     * @param appId       应用ID
     * @param loginUser   登录用户
     * @return 转换后的 Flux<String>（JSON格式）
     */
    public Flux<String> handle(TokenStream tokenStream, long appId, User loginUser) {
        // 收集数据
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        // Set<String> seenToolIds = new HashSet<>();
        long generationStartNs = System.nanoTime();

        return Flux.create(sink -> {
            // 注册取消回调
            sink.onCancel(() -> {
                log.info("客户端取消订阅，appId: {}", appId);
            });

            tokenStream.onPartialResponse((String partialResponse) -> {
                        if (sink.isCancelled()) return;

                        // 1. 发送给前端（包装为JSON）
                        String jsonResponse = JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", partialResponse));
                        sink.next(jsonResponse);

                        // 2. 收集历史记录
                        chatHistoryStringBuilder.append(partialResponse);
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        if (sink.isCancelled()) {
                            log.info("已取消，跳过 Vue 项目构建");
                            return;
                        }
                        
                        /*
                        // 3. 触发 Vue 项目构建 
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                        vueProjectBuilder.buildProjectAsync(projectPath);
                        */

                        String aiResponse = chatHistoryStringBuilder.toString();

                        Map<String, String> modelFiles = parseMultiFileProtocol(aiResponse);
                        if (modelFiles.isEmpty()) {
                            APP_ID_TO_FILES.remove(appId);
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", "\n\n【解析】未识别到任何文件块输出，请检查模型输出格式（<<<FILE:...>>> / <<<END_FILE>>> / <<<DONE>>>）。\n"
                            )));
                        } else {
                            APP_ID_TO_FILES.put(appId, Collections.unmodifiableMap(modelFiles));

                            boolean wrote = writeVueProjectToDisk(appId, modelFiles);
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", wrote ? "\n\n【落盘】已将生成的 Vue 项目写入本地源码目录。\n" : "\n\n【落盘】写入本地源码目录失败（请查看后端日志）。\n"
                            )));

                            String baseKey = buildSourceBaseKey(appId);
                            boolean uploaded = cosManager.uploadTextFiles(baseKey, modelFiles);
                            if (uploaded) {
                                String normalizedBaseKey = ensureDirKey(baseKey);
                                stringRedisTemplate.opsForValue().set(String.format("code:source:latest:%d", appId), normalizedBaseKey);
                                APP_ID_TO_FILES.remove(appId);
                                sink.next(JSONUtil.toJsonStr(Map.of(
                                        "type", "ai_response",
                                        "data", "\n\n【源码上传】已上传到云端源码目录。\n"
                                )));
                            } else {
                                sink.next(JSONUtil.toJsonStr(Map.of(
                                        "type", "ai_response",
                                        "data", "\n\n【源码上传】上传失败，请稍后重试。\n"
                                )));
                            }
                        }

                        // 存入后端对话历史
                        chatMessageRouter.route(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());

                        long totalDurationMs = (System.nanoTime() - generationStartNs) / 1_000_000;
                        log.info("项目生成总耗时 appId: {}, durationMs: {}", appId, totalDurationMs);

                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);

                        long totalDurationMs = (System.nanoTime() - generationStartNs) / 1_000_000;
                        log.error("项目生成失败 appId: {}, durationMs: {}", appId, totalDurationMs, error);
                        
                        // 记录错误历史
                        String errorMessage = "AI回复失败: " + error.getMessage();
                        chatMessageRouter.route(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    })
                    .start();
                    /*
                    .beforeToolExecution((beforeToolExecutionHandler) -> {
                        if (sink.isCancelled()) return;
                            String toolId = beforeToolExecutionHandler.request().id();
                            if (toolId != null) {
                                toolStartTimeNs.put(toolId, System.nanoTime());
                            }
                            // 如果工具是第一次出现，添加说明
                            if (toolId != null && !seenToolIds.contains(toolId)) {
                                seenToolIds.add(toolId);
                                String marker = "\n\n[选择工具] 写入文件\n\n";
                                chatHistoryStringBuilder.append(marker);
                                sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", marker)));  // 发给前端作为一段说明
                            }
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        if (sink.isCancelled()) return;
                        
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        String toolId = toolExecution.request().id();
                        String toolName = toolExecution.request().name();
                        Long startNs = toolStartTimeNs.remove(toolId);
                        if (startNs != null) {
                            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                            long durationSeconds = durationMs / 1000;
                            log.info("工具执行耗时 appId: {}, toolName: {}, toolId: {}, 消耗时间: {}", appId, toolName, toolId, durationSeconds);
                        } else {
                            log.warn("工具执行耗时未知 appId: {}, toolName: {}, toolId: {}", appId, toolName, toolId);
                        }
                        String result = formatToolExecutionResult(toolExecutedMessage);  // 里边解析 relativeFilePath & content
                        String output = "\n\n" + result + "\n\n";

                        // 1. 完整记录到历史（保证上下文完整）
                        chatHistoryStringBuilder.append(output);

                        // 2. 尝试解析并发送结构化数据给前端
                        try {
                            JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                            String relativeFilePath = jsonObject.getStr("relativeFilePath");
                            String content = jsonObject.getStr("content");

                            if (relativeFilePath != null && content != null) {
                                // 发送文件数据事件
                                Map<String, Object> fileData = Map.of(
                                    "filePath", relativeFilePath,
                                    "content", content
                                );
                                sink.next(JSONUtil.toJsonStr(Map.of("type", "file_generated", "data", JSONUtil.toJsonStr(fileData))));
                            }

                            if (relativeFilePath != null) {
                                // 发送简短的提示消息到聊天界面
                                String simpleMsg = "\n> 已生成文件: `" + relativeFilePath + "`\n";
                                sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", simpleMsg)));
                            } else {
                                sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", output)));
                            }

                        } catch (Exception e) {
                            // 解析失败，回退到发送完整文本
                            log.error("解析工具参数失败", e);
                            sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", output)));
                        }
                    })
                    */

        });
    }

    /**
     * 格式化工具执行结果，用于历史记录展示
     */
    private Map<String, String> parseMultiFileProtocol(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }
        int doneIndex = text.lastIndexOf(DONE_MARKER);
        String input = doneIndex >= 0 ? text.substring(0, doneIndex) : text;

        Map<String, String> files = new LinkedHashMap<>();
        int idx = 0;
        while (true) {
            int start = input.indexOf(FILE_MARKER_PREFIX, idx);
            if (start < 0) {
                break;
            }
            int pathStart = start + FILE_MARKER_PREFIX.length();
            int pathEnd = input.indexOf(FILE_MARKER_SUFFIX, pathStart);
            if (pathEnd < 0) {
                break;
            }
            String rawPath = input.substring(pathStart, pathEnd).trim();
            String relativePath = normalizeRelativePath(rawPath);

            int contentStart = pathEnd + FILE_MARKER_SUFFIX.length();
            if (contentStart < input.length() && input.charAt(contentStart) == '\r') {
                contentStart++;
            }
            if (contentStart < input.length() && input.charAt(contentStart) == '\n') {
                contentStart++;
            }

            int end = input.indexOf(END_FILE_MARKER, contentStart);
            if (end < 0) {
                break;
            }
            String content = input.substring(contentStart, end);
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            } else if (content.endsWith("\n")) {
                content = content.substring(0, content.length() - 1);
            }
            if (relativePath != null) {
                files.put(relativePath, content);
            }
            idx = end + END_FILE_MARKER.length();
        }
        return files;
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String p = relativePath.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isBlank()) {
            return null;
        }
        if (p.contains("..") || p.contains(":") || p.contains("\u0000")) {
            return null;
        }
        return p;
    }

    private String buildSourceBaseKey(long appId) {
        String prefix = (sourceCosPrefix == null || sourceCosPrefix.isBlank()) ? "/source-code" : sourceCosPrefix.trim();
        prefix = prefix.replace('\\', '/');
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return String.format("%s/%d/%d", prefix, appId, System.currentTimeMillis());
    }

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

    private boolean writeVueProjectToDisk(long appId, Map<String, String> relativePathToContent) {
        if (relativePathToContent == null || relativePathToContent.isEmpty()) {
            return false;
        }
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
        try {
            FileUtil.del(projectPath);
            FileUtil.mkdir(projectPath);

            for (Map.Entry<String, String> entry : relativePathToContent.entrySet()) {
                String relativePath = normalizeRelativePath(entry.getKey());
                if (relativePath == null) {
                    continue;
                }
                File target = new File(projectPath, relativePath);
                File parent = target.getParentFile();
                if (parent != null) {
                    FileUtil.mkdir(parent);
                }
                String content = entry.getValue() == null ? "" : entry.getValue();
                FileUtil.writeString(content, target, StandardCharsets.UTF_8);
            }
            return true;
        } catch (Exception e) {
            log.error("写入 Vue 项目到本地失败, appId: {}, error: {}", appId, e.getMessage(), e);
            return false;
        }
    }

    private String formatToolExecutionResult(ToolExecutedMessage toolExecutedMessage) {
        try {
            JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
            String relativeFilePath = jsonObject.getStr("relativeFilePath");
            String suffix = FileUtil.getSuffix(relativeFilePath);
            String content = jsonObject.getStr("content");
            int contentLength = content == null ? 0 : content.length();
            String fileType = (suffix == null || suffix.isBlank()) ? "unknown" : suffix;
            return String.format("""
                    [工具调用] 写入文件 %s
                    类型: %s
                    大小: %d 字符
                    """, relativeFilePath, fileType, contentLength);
        } catch (Exception e) {
            log.error("格式化工具执行结果失败", e);
            return "[工具执行结果格式化失败]";
        }
    }
}
