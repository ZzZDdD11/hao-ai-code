package com.hao.haoaicode.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.model.message.*;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.core.builder.VueProjectBuilder;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ChatMessageRouter chatMessageRouter;

    @Resource
    private VueProjectBuilder vueProjectBuilder;
    // 用于累积不完整的 JSON 块
    private final StringBuilder jsonBuffer = new StringBuilder();

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        return originFlux
                .map(chunk -> {
                    // 解析每个 JSON 消息块
                    return handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds);
                })
                .filter(StrUtil::isNotEmpty) // 过滤空字串
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatMessageRouter.route(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatMessageRouter.route(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });

    }

    /**
     * 解析并收集 TokenStream 数据
     */
    private String handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder, Set<String> seenToolIds) {
        jsonBuffer.append(chunk);
        String currentContent = jsonBuffer.toString();

        // 尝试解析缓冲区中的完整 JSON
        try {
            // 解析 JSON
            StreamMessage streamMessage = JSONUtil.toBean(currentContent, StreamMessage.class);
            jsonBuffer.setLength(0); // 成功解析后清空缓冲区

            StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
            switch (typeEnum) {
                case AI_RESPONSE -> {
                    AiResponseMessage aiMessage = JSONUtil.toBean(currentContent, AiResponseMessage.class);
                    String data = aiMessage.getData();
                    // 直接拼接响应
                    chatHistoryStringBuilder.append(data);
                    return data;
                }
                case TOOL_REQUEST -> {
                    ToolRequestMessage toolRequestMessage = JSONUtil.toBean(currentContent, ToolRequestMessage.class);
                    String toolId = toolRequestMessage.getId();
                    // 检查是否是第一次看到这个工具 ID
                    if (toolId != null && !seenToolIds.contains(toolId)) {
                        // 第一次调用这个工具，记录 ID 并完整返回工具信息
                        seenToolIds.add(toolId);
                        return "\n\n[选择工具] 写入文件\n\n";
                    } else {
                        // 不是第一次调用这个工具，直接返回空
                        return "";
                    }
                }
                case TOOL_EXECUTED -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(currentContent, ToolExecutedMessage.class);
                    JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                    String relativeFilePath = jsonObject.getStr("relativeFilePath");
                    String suffix = FileUtil.getSuffix(relativeFilePath);
                    String content = jsonObject.getStr("content");
                    String result = String.format("""
                        [工具调用] 写入文件 %s
                        ```%s
                        %s
                        ```
                        """, relativeFilePath, suffix, content);
                    // 输出前端和要持久化的内容
                    String output = String.format("\n\n%s\n\n", result);
                    chatHistoryStringBuilder.append(output);
                    return output;
                }
                default -> {
                    log.error("不支持的消息类型: {}", typeEnum);
                    return "";
                }
            }
        } catch (cn.hutool.json.JSONException e) {
            // 如果不是完整的 JSON，就继续累积
            log.debug("Received incomplete or malformed JSON chunk, buffering: {}", chunk);
            return "";
        } catch (Exception e) {
            log.error("Error processing JSON chunk: {} with content {}", e.getMessage(), currentContent, e);
            jsonBuffer.setLength(0); // 发生其他异常时清空缓冲区，避免影响后续处理
            return "";
        }
    }
}
