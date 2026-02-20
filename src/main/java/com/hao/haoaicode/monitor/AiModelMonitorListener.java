package com.hao.haoaicode.monitor;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
/**
 * - LangChain4j 的回调监听器 ，挂在“模型调用生命周期”上。
- 在不同阶段回调：
    - onRequest：请求开始时调用
    - onResponse：收到响应时调用
    - onError：发生错误时调用
 */
@Component
@Slf4j
public class AiModelMonitorListener implements ChatModelListener {

    // 用于存储请求开始时间的键
    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    // 用于监控上下文传递（因为请求和响应事件的触发不是同一个线程）
    private static final String MONITOR_CONTEXT_KEY = "monitor_context";
    
    @Resource
    private AiModelMetricsCollector aiModelMetricsCollector;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // 记录请求开始时间
        requestContext.attributes().put(REQUEST_START_TIME_KEY, Instant.now());
        
        // 从监控上下文中获取信息
        MonitorContext context = MonitorContextHolder.getContext();
        
        // ✅ 添加空指针检查
        if (context != null) {
            String userId = context.getUserId();
            String appId = context.getAppId();
            requestContext.attributes().put(MONITOR_CONTEXT_KEY, context);
        }
        
        // 获取模型名称
        String modelName = requestContext.chatRequest().modelName();

        try {
            List<ChatMessage> messages = requestContext.chatRequest().messages();
            int messageCount = messages != null ? messages.size() : 0;
            String json = ChatMessageSerializer.messagesToJson(messages != null ? messages : List.of());
            int promptBytes = json.getBytes(StandardCharsets.UTF_8).length;
            aiModelMetricsCollector.recordPromptPayload(modelName, messageCount, promptBytes);
        } catch (Exception e) {
            log.debug("记录 prompt 体量失败, modelName: {}", modelName, e);
        }

        // 记录请求指标
        aiModelMetricsCollector.recordRequest(modelName, "started");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        // 从属性中获取监控信息（由 onRequest 方法存储）
        Map<Object, Object> attributes = responseContext.attributes();
        // 从监控上下文中获取信息
        MonitorContext context = (MonitorContext) attributes.get(MONITOR_CONTEXT_KEY);
        
        // ✅ 添加空指针检查
        String userId = context != null ? context.getUserId() : "unknown";
        String appId = context != null ? context.getAppId() : "unknown";
        
        // 获取模型名称
        String modelName = responseContext.chatResponse().modelName();
        // 记录成功请求
        aiModelMetricsCollector.recordRequest( modelName, "success");
        // 记录响应时间
        recordResponseTime(attributes, userId, appId, modelName);
        // 记录 Token 使用情况
        recordTokenUsage(responseContext, userId, appId, modelName);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        // 从监控上下文中获取信息
        MonitorContext context = MonitorContextHolder.getContext();
        
        // ✅ 添加空指针检查
        String userId = context != null ? context.getUserId() : "unknown";
        String appId = context != null ? context.getAppId() : "unknown";
        
        // 获取模型名称和错误类型
        String modelName = errorContext.chatRequest().modelName();
        String errorMessage = errorContext.error().getMessage();
        
        // 记录失败请求
        aiModelMetricsCollector.recordRequest(modelName, "error");
        aiModelMetricsCollector.recordError(modelName, errorMessage);
        
        // 记录响应时间（即使是错误响应）
        Map<Object, Object> attributes = errorContext.attributes();
        recordResponseTime(attributes, userId, appId, modelName);
    }


    /**
     * 记录响应时间
     */
    private void recordResponseTime(Map<Object, Object> attributes, String userId, String appId, String modelName) {
        Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
        Duration responseTime = Duration.between(startTime, Instant.now());
        aiModelMetricsCollector.recordResponseTime( appId, modelName, responseTime);
    }

    /**
     * 记录Token使用情况
     */
    private void recordTokenUsage(ChatModelResponseContext responseContext, String userId, String appId, String modelName) {
        TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
        
        if (tokenUsage != null) {
            aiModelMetricsCollector.recordTokenUsage(modelName, "input", tokenUsage.inputTokenCount());
            aiModelMetricsCollector.recordTokenUsage(modelName, "output", tokenUsage.outputTokenCount());
            aiModelMetricsCollector.recordTokenUsage(modelName, "total", tokenUsage.totalTokenCount());

            aiModelMetricsCollector.recordTokenUsageSample(modelName, "input", tokenUsage.inputTokenCount());
            aiModelMetricsCollector.recordTokenUsageSample(modelName, "output", tokenUsage.outputTokenCount());
            aiModelMetricsCollector.recordTokenUsageSample(modelName, "total", tokenUsage.totalTokenCount());
        }
    }
}
