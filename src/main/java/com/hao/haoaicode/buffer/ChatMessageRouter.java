package com.hao.haoaicode.buffer;

import com.hao.haoaicode.model.dto.chathistory.ChatHistoryDTO;
import com.hao.haoaicode.model.enums.StorageTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 聊天消息路由器
 * 
 * 职责：
 * 1. 推送消息到缓冲队列
 * 2. 由批量刷盘服务写入 MySQL
 */
@Slf4j
@Component
public class ChatMessageRouter {

    @Resource
    private MessageBufferService messageBufferService;

    /**
     * 路由消息到缓冲队列
     *
     * @param appId       应用ID
     * @param message     消息内容
     * @param messageType 消息类型 (user/ai)
     * @param userId      用户ID
     */
    public void route(Long appId, String message, String messageType, Long userId) {
        ChatHistoryDTO dto = ChatHistoryDTO.builder()
                .appId(appId)
                .userId(userId)
                .messageType(messageType)
                .createTime(LocalDateTime.now())
                .build();

        int size = message.getBytes(StandardCharsets.UTF_8).length;

        dto.setMessage(message);
        dto.setStorageType(StorageTypeEnum.DIRECT.getValue());
        log.debug("消息直接存储到 MySQL, size: {} bytes", size);

        // 推送到缓冲队列
        messageBufferService.push(dto);
    }

}
