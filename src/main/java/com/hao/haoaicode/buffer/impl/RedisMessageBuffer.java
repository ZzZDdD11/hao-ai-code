package com.hao.haoaicode.buffer.impl;

import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.buffer.MessageBufferService;
import com.hao.haoaicode.model.dto.chathistory.ChatHistoryDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 实现的消息缓冲服务
 * 
 * 使用 Redis List 作为缓冲队列
 * LPUSH 入队，RPOP 出队（FIFO）
 * 
 * @Primary 标记为默认实现，未来可通过配置切换到 MQ 实现
 */
@Slf4j
@Component
@Primary
public class RedisMessageBuffer implements MessageBufferService {

    /**
     * Redis 缓冲队列的 key
     */
    private static final String BUFFER_KEY = "chat:history:buffer";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void push(ChatHistoryDTO message) {
        try {
            String json = JSONUtil.toJsonStr(message);
            stringRedisTemplate.opsForList().leftPush(BUFFER_KEY, json);
            log.debug("消息入队成功, appId: {}", message.getAppId());
        } catch (Exception e) {
            log.error("消息入队失败: {}", e.getMessage(), e);
            throw new RuntimeException("消息入队失败", e);
        }
    }

    @Override
    public List<ChatHistoryDTO> pop(int batchSize) {
        List<ChatHistoryDTO> result = new ArrayList<>();
        try {
            for (int i = 0; i < batchSize; i++) {
                String json = stringRedisTemplate.opsForList().rightPop(BUFFER_KEY);
                if (json == null) {
                    // 队列已空
                    break;
                }
                ChatHistoryDTO dto = JSONUtil.toBean(json, ChatHistoryDTO.class);
                result.add(dto);
            }
            if (!result.isEmpty()) {
                log.debug("批量出队 {} 条消息", result.size());
            }
        } catch (Exception e) {
            log.error("批量出队失败: {}", e.getMessage(), e);
        }
        return result;
    }

    @Override
    public long size() {
        Long size = stringRedisTemplate.opsForList().size(BUFFER_KEY);
        return size != null ? size : 0;
    }
}
