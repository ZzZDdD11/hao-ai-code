package com.hao.haoaicode.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
/**
 * Micrometer封装层，对外提供记录指标的方法
 */
@Component
@Slf4j
public class AiModelMetricsCollector {

    @Resource
    private MeterRegistry meterRegistry;


    /**
     * 记录请求次数
     * 注意：移除了 user_id，防止基数爆炸
     */
    public void recordRequest( String modelName, String status) {
        Counter.builder("ai_model_requests_total")
                .description("AI模型总请求次数")
                // .tag("user_id", userId) // ❌ 千万不要加 user_id
                //.tag("app_id", appId != null ? appId : "unknown")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录错误
     * 注意：对 errorMessage 进行了归一化处理
     */
    public void recordError( String modelName, String rawErrorMessage) {
        // ✅ 关键步骤：错误归一化
        String errorType = normalizeErrorMessage(rawErrorMessage);

        Counter.builder("ai_model_errors_total")
                .description("AI模型错误次数")
                //.tag("app_id", appId != null ? appId : "unknown")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .tag("error_type", errorType) // ✅ 使用归一化后的类型
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录Token消耗（累计值）
     */
    public void recordTokenUsage(String modelName,
                                 String tokenType, long tokenCount) {
        Counter.builder("ai_model_tokens_total")
                .description("AI模型Token消耗总数")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .tag("token_type", tokenType)
                .register(meterRegistry)
                .increment(tokenCount);
    }

    /**
     * 记录单次请求的 Token 使用分布（用于识别上下文爆炸 / prompt 过大）
     */
    public void recordTokenUsageSample(String modelName,
                                       String tokenType, long tokenCount) {
        DistributionSummary.builder("ai_model_token_usage")
                .description("AI模型单次请求Token消耗")
                .baseUnit("tokens")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .tag("token_type", tokenType != null ? tokenType : "unknown")
                .register(meterRegistry)
                .record(Math.max(tokenCount, 0));
    }

    /**
     * 记录单次请求的 prompt 体量（序列化后字节数、消息条数）
     */
    public void recordPromptPayload(String modelName, int messageCount, long promptBytes) {
        DistributionSummary.builder("ai_model_prompt_bytes")
                .description("AI模型请求prompt大小（序列化后的字节数）")
                .baseUnit("bytes")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .register(meterRegistry)
                .record(Math.max(promptBytes, 0));

        DistributionSummary.builder("ai_model_prompt_messages")
                .description("AI模型请求包含的消息条数")
                .baseUnit("messages")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .register(meterRegistry)
                .record(Math.max(messageCount, 0));
    }

    /**
     * 记录响应时间
     */
    public void recordResponseTime(String appId, String modelName, Duration duration) {
        Timer.builder("ai_model_response_duration_seconds")
                .description("AI模型响应时间")
                .tag("app_id", appId != null ? appId : "unknown")
                .tag("model_name", modelName != null ? modelName : "unknown")
                // Timer 会自动生成分位图，数据量很大，绝对不能加 user_id
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 辅助方法：将复杂的报错信息转化为有限的枚举值
     */
    private String normalizeErrorMessage(String message) {
        if (message == null) {
            return "unknown";
        }
        String lower = message.toLowerCase();
        if (lower.contains("timeout")) return "timeout";
        if (lower.contains("401") || lower.contains("auth")) return "auth_error";
        if (lower.contains("429") || lower.contains("rate limit")) return "rate_limit";
        if (lower.contains("connect")) return "connection_error";
        if (lower.contains("context") && (lower.contains("length") || lower.contains("limit") || lower.contains("too long"))) {
            return "context_length";
        }
        if (lower.contains("too many tokens") || lower.contains("context_length_exceeded") || lower.contains("maximum context length")) {
            return "context_length";
        }
        return "other_error";
    }
}