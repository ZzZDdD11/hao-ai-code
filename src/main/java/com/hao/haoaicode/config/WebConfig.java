package com.hao.haoaicode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 1. 创建线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);      // 核心线程：常驻 CPU 核心数
        executor.setMaxPoolSize(50);      // 最大线程：应对 AI 生成的高峰
        executor.setQueueCapacity(100);   // 队列：缓冲请求
        executor.setThreadNamePrefix("ai-gen-thread-");
        
        // 关键点：拒绝策略。当队列满了，由调用者运行，防止丢弃 AI 生成请求
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.initialize();

        // 2. 将线程池设置到异步支持中
        configurer.setTaskExecutor(executor);
        // 3. 设置超时时间（AI 生成很慢，建议设置长一点，5分钟）
        configurer.setDefaultTimeout(300000);
    }
}
