package com.hao.haoaicode.buffer;

import com.hao.haoaicode.manager.CosManager;
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
 * 1. 判断消息大小，决定存储策略（DIRECT / COS）
 * 2. 大消息上传 COS，存储摘要 + 引用
 * 3. 推送到缓冲队列，等待批量刷盘
 */
@Slf4j
@Component
public class ChatMessageRouter {

    /**
     * 消息大小阈值：60KB
     * 超过此大小的消息将上传到 COS
     */
    private static final int SIZE_THRESHOLD = 60 * 1024;

    /**
     * 摘要长度
     */
    private static final int SUMMARY_LENGTH = 500;

    @Resource
    private MessageBufferService messageBufferService;

    @Resource
    private CosManager cosManager;

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

        if (size < SIZE_THRESHOLD) {
            // 小消息：直接存储
            dto.setMessage(message);
            dto.setStorageType(StorageTypeEnum.DIRECT.getValue());
            log.debug("小消息直接存储, size: {} bytes", size);
        } else {
            // 大消息：上传 COS，存摘要
            String cosKey = uploadToCos(appId, userId, message);
            if (cosKey != null) {
                String summary = generateSummary(message);
                dto.setMessage(summary);
                dto.setContentRef(cosKey);
                dto.setStorageType(StorageTypeEnum.COS.getValue());
                log.info("大消息上传COS, size: {} bytes, key: {}", size, cosKey);
            } else {
                // COS 上传失败，降级为直接存储（可能会被截断）
                dto.setMessage(message.substring(0, Math.min(message.length(), 60000)));
                dto.setStorageType(StorageTypeEnum.DIRECT.getValue());
                log.warn("COS上传失败，降级为直接存储（截断）");
            }
        }

        // 推送到缓冲队列
        messageBufferService.push(dto);
    }

    /**
     * 上传大消息到 COS
     */
    private String uploadToCos(Long appId, Long userId, String content) {
        String key = String.format("/chat-history/%d/%d/%d.json", 
                appId, userId, System.currentTimeMillis());
        return cosManager.uploadContent(key, content);
    }

    /**
     * 生成消息摘要
     */
    private String generateSummary(String message) {
        if (message.length() <= SUMMARY_LENGTH) {
            return message;
        }
        return message.substring(0, SUMMARY_LENGTH) + "...[完整内容已存储至COS]";
    }
}
