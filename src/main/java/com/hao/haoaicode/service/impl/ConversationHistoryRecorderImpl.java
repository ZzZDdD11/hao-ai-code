package com.hao.haoaicode.service.impl;

import org.springframework.stereotype.Service;

import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.service.ConversationHistoryRecorder;

import jakarta.annotation.Resource;
@Service
public class ConversationHistoryRecorderImpl implements ConversationHistoryRecorder {

    @Resource
    private ChatMessageRouter chatMessageRouter;
    /**
     * 记录用户消息
     */
    @Override
    public void recordUserMessage(Long appId, String content, Long userId) {
        chatMessageRouter.route(appId, content, ChatHistoryMessageTypeEnum.USER.getValue(),userId);
    }
    /**
     * 记录AI消息
     */
    @Override
    public void recordAiMessage(Long appId, String content, Long userId) {
        chatMessageRouter.route(appId, content, ChatHistoryMessageTypeEnum.AI.getValue(),userId);
    }
    /**
     * 记录AI错误消息
     */
    @Override
    public void recordAiError(Long appId, String errorMessage, Long userId) {
        throw new UnsupportedOperationException("Unimplemented method 'recordAiError'");
    }
    
}
