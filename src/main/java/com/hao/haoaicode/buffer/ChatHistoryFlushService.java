package com.hao.haoaicode.buffer;

import com.hao.haoaicode.mapper.ChatHistoryMapper;
import com.hao.haoaicode.model.dto.chathistory.ChatHistoryDTO;
import com.hao.haoaicode.model.entity.ChatHistory;
import com.hao.haoaicode.monitor.AppMetricsCollector;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 聊天历史批量刷盘服务
 *
 * 定时从缓冲队列取出消息，批量写入 MySQL
 * 将高频单条写入转换为低频批量写入，降低数据库压力
 */
@Slf4j
@Component
public class ChatHistoryFlushService {

    /**
     * 每次批量处理的最大数量
     */
    private static final int BATCH_SIZE = 100;

    @Resource
    private MessageBufferService messageBufferService;
    @Resource
    private ChatHistoryMapper chatHistoryMapper;
    @Resource
    private AppMetricsCollector appMetricsCollector;
    /**
     * 定时批量刷盘
     * 每 5 秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    public void flush() {
        List<ChatHistoryDTO> batch = messageBufferService.pop(BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        try {
            // DTO 转换为实体
            List<ChatHistory> entities = batch.stream()
                    .map(this::toEntity)
                    .collect(Collectors.toList());

            // 批量插入
            chatHistoryMapper.insertBatch(entities);
            log.info("批量刷盘成功，写入 {} 条聊天记录", entities.size());
            // 进行指标监控
            appMetricsCollector.recordHistoricalBatch("success", entities.size());

        } catch (Exception e) {
            log.error("批量刷盘失败，{} 条消息丢失: {}", batch.size(), e.getMessage(), e);
            // TODO: 生产环境可考虑重新入队或写入死信队列
        }
    }

    /**
     * DTO 转实体
     */
    private ChatHistory toEntity(ChatHistoryDTO dto) {
        ChatHistory entity = new ChatHistory();
        entity.setAppId(dto.getAppId());
        entity.setUserId(dto.getUserId());
        entity.setMessage(dto.getMessage());
        entity.setMessageType(dto.getMessageType());
        entity.setStorageType(dto.getStorageType());
        entity.setContentRef(dto.getContentRef());
        // 设置时间字段
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(dto.getCreateTime() != null ? dto.getCreateTime() : now);
        entity.setUpdateTime(now);
        return entity;
    }
}
