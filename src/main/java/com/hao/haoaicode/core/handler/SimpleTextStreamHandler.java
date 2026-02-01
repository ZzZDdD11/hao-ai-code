package com.hao.haoaicode.core.handler;

import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.core.CodeParser;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.parser.CodeParserExecutor;
import com.hao.haoaicode.saver.CodeFileSaverExecutor;
import com.hao.haoaicode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 简单文本流处理器
 * 处理 HTML 和 MULTI_FILE 类型的流式响应
 * 职责：
 * 1. 收集流式响应
 * 2. 保存对话历史
 * 3. 解析并保存代码文件
 */
@Component
@Slf4j
public class SimpleTextStreamHandler {
    @Resource
    ChatMessageRouter chatMessageRouter;

    /**
     * 处理传统流（HTML, MULTI_FILE）
     * 直接收集完整的文本响应，并执行保存逻辑
     *
     * @param originFlux         原始流
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        return originFlux
                .map(chunk -> {
                    // 收集AI响应内容
                    aiResponseBuilder.append(chunk);
                    return JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", chunk));
                })
                .doOnComplete(() -> {
                    // 1. 流式响应完成后，添加AI消息到对话历史
                    String aiResponse = aiResponseBuilder.toString();
                    chatMessageRouter.route(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());

                    // 2. 异步执行代码解析与保存（原 Facade 中的逻辑）
                    Mono.fromCallable(() -> {
                                // 代码解析
                                Object parsedResult = CodeParserExecutor.executeParser(aiResponse, codeGenType);
                                // 代码保存
                                return CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnSuccess(savedDir -> log.info("代码保存成功: {}", savedDir.getAbsolutePath()))
                            .doOnError(e -> log.error("代码保存失败", e))
                            .subscribe();
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatMessageRouter.route(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }
}
