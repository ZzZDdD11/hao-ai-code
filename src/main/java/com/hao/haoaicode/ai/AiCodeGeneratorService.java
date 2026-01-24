package com.hao.haoaicode.ai;

import com.hao.haoaicode.ai.model.HtmlCodeResult;
import com.hao.haoaicode.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;


public interface AiCodeGeneratorService {

    /**
     * 生成html文件
     * @param sessionId 会话ID（格式：userId:appId），作为内存存历史的key
     * @param userMessage 用户消息
     * @return HTML代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@MemoryId String sessionId, @UserMessage String userMessage);

    /**
     * 生成多文件代码
     * @param sessionId 会话ID（格式：userId:appId）
     * @param userMessage 用户消息
     * @return 多文件代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(@MemoryId String sessionId, @UserMessage String userMessage);
    

    /**
     * 生成 HTML 代码（流式）
     *
     * @param sessionId 会话ID（格式：userId:appId）
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId String sessionId, @UserMessage String userMessage);

    /**
     * 生成多文件代码（流式）
     *
     * @param sessionId 会话ID（格式：userId:appId）
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId String sessionId, @UserMessage String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     *
     * @param sessionId 会话ID（格式：userId:appId）
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId String sessionId, @UserMessage String userMessage);
}
