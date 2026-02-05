package com.hao.haoaicode.service;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

public interface ConversationHistoryRecorder {

    void recordUserMessage(Long appId, String content, Long userId);
    void recordAiMessage(Long appId, String content, Long userId);
    void recordAiError(Long appId, String errorMessage, Long userId); // 可选

}
