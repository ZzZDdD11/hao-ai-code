package com.hao.haoaicode.core.handler;

import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器
 * 根据代码生成类型创建合适的流处理器：
 * 1. 传统的 Flux<String> 流（HTML、MULTI_FILE） -> SimpleTextStreamHandler
 * 2. TokenStream 格式的复杂流（VUE_PROJECT） -> JsonMessageStreamHandler
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private SimpleTextStreamHandler simpleTextStreamHandler;

    /**
     * 处理 TokenStream (VUE_PROJECT)
     */
    public Flux<String> executeTokenStream(TokenStream tokenStream,
                                           long appId,
                                           User loginUser,
                                           String userMessage,
                                           CodeGenTypeEnum codeGenType) {
        return jsonMessageStreamHandler.handle(tokenStream, appId, loginUser, userMessage, codeGenType);
    }

    /**
     * 处理普通文本流 (HTML, MULTI_FILE)
     */
    public Flux<String> executeTextStream(Flux<String> originFlux, long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        return simpleTextStreamHandler.handle(originFlux, appId, loginUser, codeGenType);
    }

    /**
     * 保持旧接口兼容（如果有需要），或者废弃
     * 目前看来主要逻辑将迁移到上述两个特定方法
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            log.warn("VUE_PROJECT should use executeTokenStream instead of doExecute with Flux<String>");
            throw new UnsupportedOperationException("VUE_PROJECT support has been migrated to TokenStream");
        }
        return simpleTextStreamHandler.handle(originFlux, appId, loginUser, codeGenType);
    }

}
