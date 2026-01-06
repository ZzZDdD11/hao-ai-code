package com.hao.haoaicode.config;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ChatMemoryStore 配置
 * 使用自定义的 RedissonChatMemoryStore 替代 LangChain4j 的 RedisChatMemoryStore
 */
@Configuration
public class RedisChatMemoryStoreConfig {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 创建基于 Redisson 的 ChatMemoryStore
     * TTL 设置为 1 天，防止 Redis 内存无限增长
     */
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new RedissonChatMemoryStore(redissonClient, Duration.ofDays(1));
    }
}
