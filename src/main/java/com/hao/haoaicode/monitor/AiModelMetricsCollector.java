package com.hao.haoaicode.monitor;

import io.micrometer.core.instrument.Counter;
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
     * 记录Token消耗
     */
    public void recordTokenUsage( String modelName,
                                 String tokenType, long tokenCount) {
        Counter.builder("ai_model_tokens_total")
                .description("AI模型Token消耗总数")
                //.tag("app_id", appId != null ? appId : "unknown")
                .tag("model_name", modelName != null ? modelName : "unknown")
                .tag("token_type", tokenType)
                .register(meterRegistry)
                .increment(tokenCount);
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
        if (message == null) return "unknown";
        if (message.contains("Timeout")) return "timeout";
        if (message.contains("401") || message.contains("Auth")) return "auth_error";
        if (message.contains("429") || message.contains("Rate limit")) return "rate_limit";
        if (message.contains("Connect")) return "connection_error";
        // 默认返回一个通用错误，避免未知错误导致 Tag 爆炸
        return "other_error";
    }
}