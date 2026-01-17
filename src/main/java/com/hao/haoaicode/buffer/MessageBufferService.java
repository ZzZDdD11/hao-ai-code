package com.hao.haoaicode.buffer;

import com.hao.haoaicode.model.dto.chathistory.ChatHistoryDTO;

import java.util.List;

/**
 * 消息缓冲服务接口
 * 
 * 业务层依赖此接口，不依赖具体实现（Redis/MQ）
 * 体现依赖倒置原则 (DIP)
 * 
 * 当前实现：RedisMessageBuffer
 * 未来扩展：可实现 RabbitMqMessageBuffer / KafkaMessageBuffer
 */
public interface MessageBufferService {

    /**
     * 推送消息到缓冲队列
     *
     * @param message 聊天历史消息
     */
    void push(ChatHistoryDTO message);

    /**
     * 批量取出消息
     *
     * @param batchSize 批量大小
     * @return 消息列表（可能为空，不会返回 null）
     */
    List<ChatHistoryDTO> pop(int batchSize);

    /**
     * 获取队列当前大小
     *
     * @return 队列中的消息数量
     */
    long size();
}
