package com.hao.haoaicode.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.TimeUnit;

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
    // 应用ID到生成的文件的映射，用于内存存储VUE项目
    private static final Cache<Long, Map<String, String>> APP_ID_TO_FILES = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    // 获取应用ID对应的所有生成文件
    public static Map<String, String> getGeneratedFiles(long appId) {
        Map<String, String> files = APP_ID_TO_FILES.getIfPresent(appId);
        return files != null ? files : Collections.emptyMap();
    }
    // 清除应用ID对应的所有文件
    public static void clearGeneratedFiles(long appId) {

        APP_ID_TO_FILES.invalidate(appId);
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
        StringBuilder displayBuffer = new StringBuilder();
        // 用来记住“已经发给前端的干净内容长度”，
        // 因为现在我们对 累积缓冲 做清洗，每次清洗都会得到一份“完整的干净文本”。如果不做截断，前端会收到 重复内容 。
        // 所以 lastSentLen 的作用是：
        //- 记录上一次已经发送到前端的字符数
        //- 本次只把新增的那一段发出去（ displayResponse.substring(lastSentLen) ）
        int[] lastSentLen = new int[]{0};
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

                        displayBuffer.append(partialResponse);
                        String displayResponse = displayBuffer.toString()
                                .replaceAll("(?s)<<<FILE:.*?>>>", "")
                                .replaceAll("<<<END_FILE>>>", "")
                                .replaceAll("<<<DONE>>>", "");
                        if (displayResponse.length() > lastSentLen[0]) {
                            String delta = displayResponse.substring(lastSentLen[0]);
                            lastSentLen[0] = displayResponse.length();
                            String jsonResponse = JSONUtil.toJsonStr(
                                Map.of("type", "ai_response", "data", delta)
                            );
                            sink.next(jsonResponse);
                        }
                        chatHistoryStringBuilder.append(partialResponse);
                    })
                    // 大模型流式输出结束后的收尾逻辑（只会调用一次）
                    .onCompleteResponse((ChatResponse response) -> {
                        // 如果前端已经取消订阅（例如用户中途终止），则不再进行后续构建/上传等操作
                        if (sink.isCancelled()) {
                            log.info("已取消，跳过 Vue 项目构建");
                            return;
                        }
                        
                        /*
                        // 3. 触发 Vue 项目构建 
                        // 早期版本：直接在本地某个目录构建一个 Vue 项目
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                        vueProjectBuilder.buildProjectAsync(projectPath);
                        */
                        // 完整的 AI 响应（包含所有流式 partialResponse 的累积内容）
                        String aiResponse = chatHistoryStringBuilder.toString();
                        // 规定的“多文件协议”格式：
                        // 1. 每个文件块以 <<<FILE:filename>>> 开头，以 <<<END_FILE>>> 结尾
                        // 2. 所有文件块结束后，以 <<<DONE>>> 结尾
                        // 这里根据上述协议解析出多个文件
                   
                        // 解析多文件协议，得到模型生成的所有文件：key 为相对路径，value 为文件内容
                        Map<String, String> modelFiles = parseMultiFileProtocol(aiResponse); // key: 相对路径，value: 文件内容
                        if (modelFiles.isEmpty()) {
                            // 未解析出任何文件：清理缓存，并提示前端检查模型输出格式
                            APP_ID_TO_FILES.invalidate(appId);
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", "\n\n【解析】未识别到任何文件块输出，请检查模型输出格式（<<<FILE:...>>> / <<<END_FILE>>> / <<<DONE>>>）。\n"
                            )));
                        } else {
                            // 在内存中缓存当前 appId 对应的文件集（只读视图，避免被外部修改）,用于即时预览
                            APP_ID_TO_FILES.put(appId, Collections.unmodifiableMap(modelFiles));
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", "\n\n【预览】已将生成的 Vue 项目写入内存缓存。可立即预览（30分钟有效）\n"
                            )));

                            // 计算云端源码目录的 baseKey（相当于一个“目录前缀”）
                            String baseKey = buildSourceBaseKey(appId);
                            // 将所有文本文件批量上传到对象存储（COS）
                            boolean uploaded = cosManager.uploadTextFiles(baseKey, modelFiles);
                            if (uploaded) {
                                // 规范化目录 key，并写入 Redis，标记当前 appId 最新源码所在的云端目录
                                String normalizedBaseKey = ensureDirKey(baseKey);
                                stringRedisTemplate.opsForValue().set(String.format("code:source:latest:%d", appId), normalizedBaseKey);
                                // 上传成功后，文件仍然保留在内存，由Caffeine 负责按过期时间淘汰
                                sink.next(JSONUtil.toJsonStr(Map.of(
                                        "type", "ai_response",
                                        "data", "\n\n【源码上传】已上传到云端源码目录。\n"
                                )));
                            } else {
                                // 上传失败，仅提示前端失败信息，文件仍保留在本地
                                sink.next(JSONUtil.toJsonStr(Map.of(
                                        "type", "ai_response",
                                        "data", "\n\n【源码上传】上传失败，请稍后重试。\n"
                                )));
                            }
                        }

                        // 将完整的 AI 回复存入后端对话历史，用于后续查看和上下文追溯
                        chatMessageRouter.route(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());

                        // 记录本次项目生成的总耗时（从开始到全部完成）
                        long totalDurationMs = (System.nanoTime() - generationStartNs) / 1_000_000;
                        log.info("项目生成总耗时 appId: {}, durationMs: {}", appId, totalDurationMs);

                        // 通知前端：流式推送已经完全结束
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
     * 在AI回复的大字符串里，解析出每一个文件的路径和内容”，
     * 并整理成一个 Map<相对路径, 文件内容>
     *
     * 协议大致格式：
     *   ###FILE: 相对路径###\n
     *   <文件内容>\n
     *   ###ENDFILE###
     * 多个文件依次追加，末尾可能带有 DONE 标记。
     */
    private Map<String, String> parseMultiFileProtocol(String text) {
        // 判空保护：没有任何内容时，直接返回空 Map，避免后续解析 NPE
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }
        // DONE_MARKER 作为整体结束标记，只取它之前的内容参与解析
        int doneIndex = text.lastIndexOf(DONE_MARKER);
        // 若存在 DONE_MARKER，则截取 [0, doneIndex)；否则使用原始文本
        String input = doneIndex >= 0 ? text.substring(0, doneIndex) : text;
        
        // 使用 LinkedHashMap 保证文件插入顺序，用于历史记录按生成顺序展示
        Map<String, String> files = new LinkedHashMap<>();
        // idx 为当前扫描起点索引
        int idx = 0;
        while (true) {
            // 从当前 idx 开始查找下一个文件块的起始标记
            int start = input.indexOf(FILE_MARKER_PREFIX, idx);
            if (start < 0) {
                // 未找到起始标记，结束整体解析
                break;
            }
            // 计算路径起止位置：位于 FILE_MARKER_PREFIX 与 FILE_MARKER_SUFFIX 之间
            int pathStart = start + FILE_MARKER_PREFIX.length();
            int pathEnd = input.indexOf(FILE_MARKER_SUFFIX, pathStart);
            if (pathEnd < 0) {
                // 缺少路径结束标记，认为协议格式异常，结束解析
                break;
            }
            // 原始路径并去掉首尾空白
            String rawPath = input.substring(pathStart, pathEnd).trim();
            // 归一化相对路径（去掉非法字符、前导分隔符等），非法则返回 null
            String relativePath = normalizeRelativePath(rawPath);

            // 文件内容起点：紧随路径结束标记之后
            int contentStart = pathEnd + FILE_MARKER_SUFFIX.length();
            // 跳过紧邻的 \r 和 \n，确保内容从新的一行真正开始
            if (contentStart < input.length() && input.charAt(contentStart) == '\r') {
                contentStart++;
            }
            if (contentStart < input.length() && input.charAt(contentStart) == '\n') {
                contentStart++;
            }

            // 查找当前文件块的结束标记
            int end = input.indexOf(END_FILE_MARKER, contentStart);
            if (end < 0) {
                // 未找到结束标记，认为协议格式已损坏，停止解析
                break;
            }
            // 取出文件内容（不包含 END_FILE_MARKER 本身）
            String content = input.substring(contentStart, end);
            // 去掉末尾多余的换行符，保证文件内容不多一行空白
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            } else if (content.endsWith("\n")) {
                content = content.substring(0, content.length() - 1);
            }
            // 只有在路径合法时才写入 Map，避免非法路径污染文件系统
            if (relativePath != null) {
                files.put(relativePath, content);
            }
            // 将扫描指针移动到本次 END_FILE_MARKER 之后，继续寻找下一个文件块
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
    // 将 Vue 项目源码写入本地磁盘
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
