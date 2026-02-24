package com.hao.haoaicode.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.hao.haoaicode.ai.model.message.ToolExecutedMessage;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.service.ConversationHistoryRecorder;
import com.hao.haoaicode.service.ProjectGenerationPostProcessor;
import com.hao.haoaicode.service.SemanticCacheService;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应
 * 职责：
 * 1. 将 TokenStream 转换为前端可用的 JSON 字符串流
 * 2. 收集对话历史
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ChatMessageRouter chatMessageRouter;

    @Resource
    private ConversationHistoryRecorder conversationHistoryRecorder;

    @Resource
    private ProjectGenerationPostProcessor projectGenerationPostProcessor;

    @Resource
    private SemanticCacheService semanticCacheService;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 直接消费 TokenStream，避免了反复序列化/反序列化 JSON 的开销
     *
     * @param tokenStream 原始 TokenStream
     * @param appId       应用ID
     * @param loginUser   登录用户
     * @param userMessage 本次用户输入的提示词（用于语义缓存等后处理）
     * @param codeGenType 代码生成类型
     * @return 转换后的 Flux<String>（JSON格式）
     */
    public Flux<String> handle(TokenStream tokenStream,
                               long appId,
                               User loginUser,
                               String userMessage,
                               CodeGenTypeEnum codeGenType) {
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
        // 记录开始的时间，用来统计耗时，nanoTime返回的是纳秒
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
                        
                        // 完整的 AI 回复，转换成字符串
                        String aiResponse = chatHistoryStringBuilder.toString();
                        // - 解析多文件协议 → 得到 (path → content)
                        // - 合并进 Caffeine 缓存（用于预览）
                        // - 上传源码文件到 COS，更新 Redis 里的“最新源码路径”，并记录指标。
                        ProjectGenerationPostProcessor.ProjectGenerationResult result =
                                projectGenerationPostProcessor.processGeneration(appId, aiResponse);

                        if (!result.hasFiles()) {
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", "\n\n【解析】未识别到任何文件块输出，请检查模型输出格式（<<<FILE:...>>> / <<<END_FILE>>> / <<<DONE>>>）。\n"
                            )));
                        } else {
                            sink.next(JSONUtil.toJsonStr(Map.of(
                                    "type", "ai_response",
                                    "data", "\n\n【预览】已将生成的 Vue 项目写入内存缓存。可立即预览（30分钟有效）\n"
                            )));
                            if (result.isUploadSuccess()) {
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

                        // 将完整的 AI 回复存入后端对话历史，用于后续查看和上下文追溯
                        conversationHistoryRecorder.recordAiMessage(appId, aiResponse, loginUser.getId());

                        // 在代码生成完成后，将本次生成记录写入语义缓存版本记录
                        try {
                            if (result.isUploadSuccess()
                                    && result.getSourceBaseKey() != null
                                    && codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
                                semanticCacheService.savaCache(
                                        userMessage,
                                        appId,
                                        loginUser.getId(),
                                        codeGenType,
                                        result.getSourceBaseKey(),
                                        0.0d
                                );
                            }
                        } catch (Exception e) {
                            log.warn("保存语义缓存记录失败 appId: {}, error: {}", appId, e.getMessage());
                        }

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
             
        });
    }


}
