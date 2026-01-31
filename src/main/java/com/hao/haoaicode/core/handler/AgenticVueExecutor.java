package com.hao.haoaicode.core.handler;

import com.hao.haoaicode.ai.AiCodeGeneratorService;
import com.hao.haoaicode.ai.AiCodeGeneratorServiceFactory;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Slf4j
public class AgenticVueExecutor {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    public Flux<String> execute(String sessionId,
                                long appId,
                                User loginUser,
                                String userRequirement,
                                String planJson,
                                String scaffoldSteps) {
        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getService(CodeGenTypeEnum.VUE_PROJECT_AGENTIC);

        StringBuilder userMessageBuilder = new StringBuilder();
        userMessageBuilder.append("【用户原始需求】\n").append(userRequirement).append("\n\n");
        userMessageBuilder.append("【项目规划(JSON)】\n").append(planJson).append("\n\n");
        userMessageBuilder.append("【脚手架步骤计划】\n").append(scaffoldSteps).append("\n");

        String enrichedUserMessage = userMessageBuilder.toString();

        log.info("[Agentic] 调用 Vue 代码生成服务, sessionId={}, appId={}", sessionId, appId);

        try {
            TokenStream tokenStream = service.generateVueProjectCodeStream(sessionId, enrichedUserMessage);
            return streamHandlerExecutor.executeTokenStream(tokenStream, appId, loginUser);
        } catch (IllegalArgumentException e) {
            log.error("[Agentic] 执行阶段内部模板错误: {}", e.getMessage(), e);
            String message = "Agentic 执行阶段失败：内部模板缺少变量，当前仅展示规划和步骤，不执行代码生成。错误：" + e.getMessage() + "\n";
            return Flux.just(message);
        } catch (Exception e) {
            log.error("[Agentic] 执行阶段异常", e);
            String message = "Agentic 执行阶段出现异常：" + e.getMessage() + "\n";
            return Flux.just(message);
        }
    }
}
