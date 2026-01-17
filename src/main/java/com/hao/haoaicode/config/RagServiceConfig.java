package com.hao.haoaicode.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RAG 服务配置
 */
@Configuration
public class RagServiceConfig {
    
    @Value("${rag.service.timeout:120000}")
    private Long timeout;
    
    /**
     * 配置用于调用 RAG 服务的 RestTemplate
     */
    @Bean("ragRestTemplate")
    public RestTemplate ragRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))  // 连接超时 10 秒
                .setReadTimeout(Duration.ofMillis(timeout)) // 读取超时使用配置值（默认 120 秒）
                .build();
    }
}
