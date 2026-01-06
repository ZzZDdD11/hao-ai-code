package com.hao.haoaicode.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redisson 的 ChatMemoryStore 实现
 * 替代 LangChain4j 的 RedisChatMemoryStore（使用 Jedis）
 * 
 * 优势：
 * 1. Redisson 连接池更稳定，不会出现 Connection reset 问题
 * 2. 支持自动重连和故障转移
 * 3. 与项目现有的 RedissonClient 复用，减少连接数
 */
@Slf4j
public class RedissonChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat_memory:";

    private final RedissonClient redissonClient;
    private final Duration ttl;

    public RedissonChatMemoryStore(RedissonClient redissonClient, Duration ttl) {
        this.redissonClient = redissonClient;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isEmpty()) {
                return new ArrayList<>();
            }
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.error("Failed to get messages from Redis, memoryId: {}", memoryId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            if (messages == null || messages.isEmpty()) {
                bucket.delete();
                return;
            }
            String json = ChatMessageSerializer.messagesToJson(messages);
            bucket.set(json, ttl);
            log.debug("Updated messages in Redis, memoryId: {}, count: {}", memoryId, messages.size());
        } catch (Exception e) {
            log.error("Failed to update messages in Redis, memoryId: {}", memoryId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.delete();
            log.debug("Deleted messages from Redis, memoryId: {}", memoryId);
        } catch (Exception e) {
            log.error("Failed to delete messages from Redis, memoryId: {}", memoryId, e);
        }
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId.toString();
    }
}
