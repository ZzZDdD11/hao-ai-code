package com.hao.haoaicode.config;

import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.mapper.ChatHistoryMapper;
import com.hao.haoaicode.model.entity.ChatHistory;
import com.hao.haoaicode.model.enums.StorageTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Redisson 的 ChatMemoryStore 实现
 * 
 * 特性：
 * 1. Redis 作为热数据缓存，快速读取
 * 2. MySQL 作为持久化存储，Redis 未命中时自动恢复
 * 3. 支持 COS 大消息的透明加载
 * 4. Cache-Aside 模式保证数据一致性
 */
@Slf4j
public class RedissonChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat_memory:";
    private static final int MAX_MESSAGES = 20;

    private final RedissonClient redissonClient;
    private final Duration ttl;
    private final ChatHistoryMapper chatHistoryMapper;
    private final CosManager cosManager;

    public RedissonChatMemoryStore(RedissonClient redissonClient, 
                                    Duration ttl,
                                    ChatHistoryMapper chatHistoryMapper,
                                    CosManager cosManager) {
        this.redissonClient = redissonClient;
        this.ttl = ttl;
        this.chatHistoryMapper = chatHistoryMapper;
        this.cosManager = cosManager;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            // 1. 先查 Redis
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();

            if (json != null && !json.isEmpty()) {
                return ChatMessageDeserializer.messagesFromJson(json);
            }

            // 2. Redis 未命中，从 MySQL 恢复
            List<ChatMessage> messages = loadFromMySQL(memoryId);

            // 3. 回填 Redis
            if (!messages.isEmpty()) {
                String recoveredJson = ChatMessageSerializer.messagesToJson(messages);
                bucket.set(recoveredJson, ttl);
                log.info("从 MySQL 恢复记忆到 Redis, memoryId: {}, count: {}", memoryId, messages.size());
            }

            return messages;
        } catch (Exception e) {
            log.error("Failed to get messages, memoryId: {}", memoryId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 从 MySQL 加载历史记录并转换为 LangChain4j 格式
     */
    private List<ChatMessage> loadFromMySQL(Object memoryId) {
        try {
            Long appId = parseAppId(memoryId);
            if (appId == null) {
                return new ArrayList<>();
            }

            // 查询 MySQL
            List<ChatHistory> histories = chatHistoryMapper.selectListByQuery(
                QueryWrapper.create()
                    .eq("appId", appId)
                    .eq("isDelete", 0)
                    .orderBy("createTime", true)
                    .limit(MAX_MESSAGES)
            );

            if (histories == null || histories.isEmpty()) {
                return new ArrayList<>();
            }

            // 转换为 LangChain4j ChatMessage
            return histories.stream()
                .map(this::toChatMessage)
                .filter(msg -> msg != null)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to load from MySQL, memoryId: {}", memoryId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 将 ChatHistory 转换为 LangChain4j ChatMessage
     */
    private ChatMessage toChatMessage(ChatHistory history) {
        try {
            String content = history.getMessage();

            // 如果是 COS 存储，需要从 COS 加载完整内容
            if (StorageTypeEnum.COS.getValue().equals(history.getStorageType())) {
                String cosContent = cosManager.downloadContent(history.getContentRef());
                if (cosContent != null) {
                    content = cosContent;
                }
            }

            if ("user".equals(history.getMessageType())) {
                return UserMessage.from(content);
            } else if ("ai".equals(history.getMessageType())) {
                return AiMessage.from(content);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to convert ChatHistory to ChatMessage, id: {}", history.getId(), e);
            return null;
        }
    }

    /**
     * 从 memoryId 解析 appId
     * memoryId 格式: "appId" 或 "userId:appId"
     */
    private Long parseAppId(Object memoryId) {
        if (memoryId == null) {
            return null;
        }
        String memoryIdStr = memoryId.toString();
        try {
            if (memoryIdStr.contains(":")) {
                // 格式: userId:appId
                String[] parts = memoryIdStr.split(":");
                return Long.parseLong(parts[1]);
            } else {
                // 格式: appId
                return Long.parseLong(memoryIdStr);
            }
        } catch (NumberFormatException e) {
            log.error("Failed to parse appId from memoryId: {}", memoryId, e);
            return null;
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
