package com.hao.haoaicode.service;

import com.hao.haoaicode.model.dto.chathistory.ChatHistoryQueryRequest;
import com.hao.haoaicode.model.entity.ChatHistory;
import com.hao.haoaicode.model.entity.User;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;

/**
 * 对话历史 服务层。
 *
 * @author hao
 */
public interface ChatHistoryService extends IService<ChatHistory> {
    /**
     * 对话历史功能
     * @param appId
     * @param message
     * @param messageType
     * @param userId
     * @return
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);
    
    /**
     * 关联删除功能
     */
    boolean deleteChatMessage(Long appId);

    /**
     * 构建查询
     * @param chatHistoryQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize,
                                               LocalDateTime lastCreateTime,
                                               User loginUser);

    int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount);
}
