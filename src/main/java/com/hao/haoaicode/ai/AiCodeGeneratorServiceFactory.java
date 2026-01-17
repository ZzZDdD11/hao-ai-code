package com.hao.haoaicode.ai;

import com.hao.haoaicode.ai.gradrail.PromptSafetyInputGuardrail;
import com.hao.haoaicode.ai.tools.ToolManager;
import com.hao.haoaicode.exception.BusinessException;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 用来创建Ai 服务实例
 */
@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;
    
    @Resource
    @Qualifier("openAiStreamingChatModel")
    private StreamingChatModel openAiStreamingChatModel;
    
    @Resource
    private ChatMemoryStore chatMemoryStore;
    
    @Resource
    private StreamingChatModel reasoningStreamingChatModel;
    @Resource
    private ToolManager toolManager;

    /**
     * 创建 HTML/MULTI_FILE 类型的共享服务
     */
    @Bean
    public AiCodeGeneratorService htmlAiService() {
        return AiServices.builder(AiCodeGeneratorService.class)
            .chatModel(chatModel)
            .streamingChatModel(reasoningStreamingChatModel)
            .chatMemoryProvider(memoryId -> {
                return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .chatMemoryStore(chatMemoryStore)
                    .maxMessages(20)
                    .build();
            })
            .inputGuardrails(new PromptSafetyInputGuardrail())
            .build();
    }

    /**
     * 创建 VUE_PROJECT 类型的共享服务
     */
    @Bean
    public AiCodeGeneratorService vueAiService() {
        return AiServices.builder(AiCodeGeneratorService.class)
            .streamingChatModel(reasoningStreamingChatModel)  // 使用同一个模型
            .chatMemoryProvider(memoryId -> {
                return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .chatMemoryStore(chatMemoryStore)
                    .maxMessages(20)
                    .build();
            })
            .tools(toolManager.getAllTools())
            .inputGuardrails(new PromptSafetyInputGuardrail())
            .hallucinatedToolNameStrategy(toolExecutionRequest -> 
                ToolExecutionResultMessage.from(
                    toolExecutionRequest, 
                    "Error: there is no tool called " + toolExecutionRequest.name()
                )
            )
            .build();
    }

    /**
     * 根据代码生成类型获取对应的共享服务
     */
    public AiCodeGeneratorService getService(CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case HTML, MULTI_FILE -> htmlAiService();
            case VUE_PROJECT -> vueAiService();
            default -> throw new BusinessException(
                ErrorCode.SYSTEM_ERROR,
                "不支持的代码生成类型: " + codeGenType.getValue()
            );
        };
    }
}
