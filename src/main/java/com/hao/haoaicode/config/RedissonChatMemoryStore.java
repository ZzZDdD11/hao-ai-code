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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final Set<String> CONTENT_TOOL_NAMES = Set.of("writeFile", "writeBatchFiles");

    private final RedissonClient redissonClient;
    private final Duration ttl;
    private final ChatHistoryMapper chatHistoryMapper;
    private final CosManager cosManager;
    private final Map<String, List<ChatMessage>> localCache = new ConcurrentHashMap<>();

    public RedissonChatMemoryStore(RedissonClient redissonClient, 
                                    Duration ttl,
                                    ChatHistoryMapper chatHistoryMapper,
                                    CosManager cosManager) {
        this.redissonClient = redissonClient;
        this.ttl = ttl;
        this.chatHistoryMapper = chatHistoryMapper;
        this.cosManager = cosManager;
    }

    /**
     * 读取消息
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            List<ChatMessage> cached = localCache.get(key);
            if (cached != null && !cached.isEmpty()) {
                return new ArrayList<>(cached);
            }



            // 1. 先查 Redis
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();

            if (json != null && !json.isEmpty()) {
                String sanitizedJson = sanitizeToolCallArgumentsJson(json);
                if (!sanitizedJson.equals(json)) {
                    bucket.set(sanitizedJson, ttl);
                    json = sanitizedJson;
                }
                List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
                if (messages != null && !messages.isEmpty()) {
                    localCache.put(key, new ArrayList<>(messages));
                }
                return messages;
            }

            // 2. Redis 未命中，从 MySQL 恢复
            List<ChatMessage> messages = loadFromMySQL(memoryId);

            // 3. 回填 Redis
            if (!messages.isEmpty()) {
                String recoveredJson = ChatMessageSerializer.messagesToJson(messages);
                bucket.set(recoveredJson, ttl);
                localCache.put(key, new ArrayList<>(messages));
                log.info("从 MySQL 恢复记忆到 Redis, memoryId: {}, count: {}", memoryId, messages.size());
            }

            return messages;
        } catch (Exception e) {
            log.error("Failed to get messages, memoryId: {}", memoryId, e);
            return new ArrayList<>();
        }
    }


    /**
     * 更新记忆，把信息存储到Redis中做会话窗口
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        
        String key = buildKey(memoryId);

        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            if (messages == null || messages.isEmpty()) {
                bucket.delete();
                localCache.remove(key);
                return;
            }
            localCache.put(key, new ArrayList<>(messages));
            String json = ChatMessageSerializer.messagesToJson(messages);
            String sanitizedJson = sanitizeToolCallArgumentsJson(json);
            if (!sanitizedJson.equals(json)) {
                json = sanitizedJson;
            }
            bucket.set(json, ttl);

            log.debug("Updated messages in Redis, memoryId: {}, count: {}", memoryId, messages.size());
        } catch (Exception e) {
            log.error("Failed to update messages in Redis, memoryId: {}", memoryId, e);
        }
    }

    /**
     * 删除记忆
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.delete();
            localCache.remove(key);
            log.debug("Deleted messages from Redis, memoryId: {}", memoryId);
        } catch (Exception e) {
            log.error("Failed to delete messages from Redis, memoryId: {}", memoryId, e);
        }
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId.toString();
    }


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

    private Long parseAppId(Object memoryId) {
        if (memoryId == null) {
            return null;
        }
        String memoryIdStr = memoryId.toString();
        try {
            if (memoryIdStr.contains(":")) {
                String[] parts = memoryIdStr.split(":");
                return Long.parseLong(parts[1]);
            } else {
                return Long.parseLong(memoryIdStr);
            }
        } catch (NumberFormatException e) {
            log.error("Failed to parse appId from memoryId: {}", memoryId, e);
            return null;
        }
    }

    private String sanitizeToolCallArgumentsJson(String json) {
        try {
            JSONArray messages = JSONUtil.parseArray(json);
            boolean changed = false;
            for (Object msgObj : messages) {
                if (!(msgObj instanceof JSONObject)) {
                    continue;
                }
                JSONObject msg = (JSONObject) msgObj;
                JSONArray toolRequests = msg.getJSONArray("toolExecutionRequests");
                if (toolRequests != null) {
                    changed |= sanitizeToolExecutionRequests(toolRequests);
                }
                JSONArray toolCalls = msg.getJSONArray("tool_calls");
                if (toolCalls != null) {
                    changed |= sanitizeOpenAiToolCalls(toolCalls);
                }
            }
            return changed ? JSONUtil.toJsonStr(messages) : json;
        } catch (Exception e) {
            log.warn("Failed to sanitize tool call arguments", e);
            return json;
        }
    }

    private boolean sanitizeToolExecutionRequests(JSONArray toolRequests) {
        boolean changed = false;
        for (Object reqObj : toolRequests) {
            if (!(reqObj instanceof JSONObject)) {
                continue;
            }
            JSONObject req = (JSONObject) reqObj;
            String name = req.getStr("name");
            if (!CONTENT_TOOL_NAMES.contains(name)) {
                continue;
            }
            String arguments = req.getStr("arguments");
            String sanitized = sanitizeArguments(name, arguments);
            if (sanitized != null && !sanitized.equals(arguments)) {
                req.set("arguments", sanitized);
                changed = true;
            }
        }
        return changed;
    }

    private boolean sanitizeOpenAiToolCalls(JSONArray toolCalls) {
        boolean changed = false;
        for (Object callObj : toolCalls) {
            if (!(callObj instanceof JSONObject)) {
                continue;
            }
            JSONObject call = (JSONObject) callObj;
            JSONObject function = call.getJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.getStr("name");
            if (!CONTENT_TOOL_NAMES.contains(name)) {
                continue;
            }
            String arguments = function.getStr("arguments");
            String sanitized = sanitizeArguments(name, arguments);
            if (sanitized != null && !sanitized.equals(arguments)) {
                function.set("arguments", sanitized);
                changed = true;
            }
        }
        return changed;
    }

    private String sanitizeArguments(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return arguments;
        }
        try {
            JSONObject args = JSONUtil.parseObj(arguments);
            if ("writeFile".equals(toolName)) {
                String content = args.getStr("content");
                if (content != null) {
                    args.set("contentLength", content.length());
                    args.set("contentOmitted", true);
                    args.remove("content");
                }
            } else if ("writeBatchFiles".equals(toolName)) {
                Object filesObj = args.get("files");
                if (filesObj != null) {
                    JSONArray files = JSONUtil.parseArray(filesObj);
                    for (Object fileObj : files) {
                        if (fileObj instanceof JSONObject) {
                            JSONObject file = (JSONObject) fileObj;
                            String content = file.getStr("content");
                            if (content != null) {
                                file.set("contentLength", content.length());
                                file.set("contentOmitted", true);
                                file.remove("content");
                            }
                        }
                    }
                    args.set("files", files);
                }
            }
            return JSONUtil.toJsonStr(args);
        } catch (Exception e) {
            return arguments;
        }
    }

}
