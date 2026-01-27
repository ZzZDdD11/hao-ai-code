package com.hao.haoaicode.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 配置类
 * 用于配置熔断器和重试机制的监听器
 */
@Slf4j
@Configuration
public class Resilience4jConfig {

    /**
     * 配置熔断器事件监听
     */
    @Bean
    public CircuitBreaker aiServiceCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiService");
        
        // 监听熔断器状态变化
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> 
                log.warn("AI服务熔断器状态变化: {} -> {}", 
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()))
            .onError(event -> 
                log.error("AI服务调用失败: {}", event.getThrowable().getMessage()))
            .onSuccess(event -> 
                log.debug("AI服务调用成功，耗时: {}ms", event.getElapsedDuration().toMillis()))
            .onCallNotPermitted(event -> 
                log.warn("AI服务熔断中，拒绝调用"));
        
        return circuitBreaker;
    }

    /**
     * 配置重试事件监听
     */
    @Bean
    public Retry aiServiceRetry(RetryRegistry retryRegistry) {
        Retry retry = retryRegistry.retry("aiService");
        
        // 监听重试事件
        retry.getEventPublisher()
            .onRetry(event -> 
                log.warn("AI服务重试，第{}次尝试，原因: {}", 
                    event.getNumberOfRetryAttempts(),
                    event.getLastThrowable().getMessage()))
            .onSuccess(event -> 
                log.info("AI服务重试成功，共尝试{}次", event.getNumberOfRetryAttempts()))
            .onError(event -> 
                log.error("AI服务重试失败，已尝试{}次", event.getNumberOfRetryAttempts()));
        
        return retry;
    }
}
