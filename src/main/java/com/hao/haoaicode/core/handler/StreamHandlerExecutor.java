package com.hao.haoaicode.core.handler;

import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.service.ChatHistoryService;
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

    public Flux<String> doExecute(Flux<String> originFlux,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case VUE_PROJECT ->
                    jsonMessageStreamHandler.handle(originFlux, appId, loginUser);
            case HTML, MULTI_FILE ->
                    simpleTextStreamHandler.handle(originFlux, appId, loginUser);
        };
    }

}
