package com.hao.haoaicode.model.dto.chathistory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天历史 DTO
 * 用于缓冲队列传输，包含存储类型信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 应用ID
     */
    private Long appId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 消息类型: user/ai
     */
    private String messageType;

    /**
     * 消息内容（大消息时为摘要）
     */
    private String message;

    /**
     * 存储类型: DIRECT/COS
     */
    private String storageType;

    /**
     * COS 对象引用 key（仅 COS 类型有值）
     */
    private String contentRef;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
