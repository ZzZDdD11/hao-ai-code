package com.hao.haoaicode.config;

import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.mapper.ChatHistoryMapper;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ChatMemoryStore 配置
 * 使用自定义的 RedissonChatMemoryStore 替代 LangChain4j 的 RedisChatMemoryStore
 * 
 * 特性：
 * 1. Redis 作为热数据缓存（TTL 1天）
 * 2. MySQL 作为持久化存储，Redis 未命中时自动恢复
 * 3. 支持 COS 大消息的透明加载
 */
@Configuration
public class RedisChatMemoryStoreConfig {

    @Resource
    private RedissonClient redissonClient;
    
    @Resource
    private ChatHistoryMapper chatHistoryMapper;
    
    @Resource
    private CosManager cosManager;

    /**
     * 创建基于 Redisson 的 ChatMemoryStore
     * TTL 设置为 1 天，防止 Redis 内存无限增长
     */
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new RedissonChatMemoryStore(
            redissonClient, 
            Duration.ofDays(1),
            chatHistoryMapper,
            cosManager
        );
    }
}
