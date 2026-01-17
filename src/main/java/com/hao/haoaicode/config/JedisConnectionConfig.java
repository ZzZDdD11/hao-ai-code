package com.hao.haoaicode.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Jedis 连接池配置
 * 解决 Redis 连接池空闲连接被服务器关闭后，客户端复用导致的 Connection reset 问题
 */
@Configuration
public class JedisConnectionConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:5000ms}")
    private Duration timeout;

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        // Redis 服务器配置
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(host);
        redisConfig.setPort(port);
        redisConfig.setDatabase(database);
        if (password != null && !password.isEmpty()) {
            redisConfig.setPassword(password);
        }

        // Jedis 连接池配置 - 关键配置解决 Connection reset
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        
        // 连接池大小配置
        poolConfig.setMaxTotal(8);           // 最大连接数
        poolConfig.setMaxIdle(8);            // 最大空闲连接数
        poolConfig.setMinIdle(2);            // 最小空闲连接数（保持一定数量的热连接）
        
        // ===== 关键配置：空闲连接检测 =====
        // 开启空闲连接检测（借用时检测）
        poolConfig.setTestOnBorrow(true);
        // 开启空闲连接检测（归还时检测）
        poolConfig.setTestOnReturn(true);
        // 开启空闲连接定期检测
        poolConfig.setTestWhileIdle(true);
        
        // 空闲连接检测周期（30秒检测一次）
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        
        // 连接空闲多久后被检测（60秒）
        poolConfig.setMinEvictableIdleTime(Duration.ofSeconds(60));
        
        // 软性空闲时间（配合 minIdle 使用，超过 minIdle 的连接空闲超过此时间会被回收）
        poolConfig.setSoftMinEvictableIdleTime(Duration.ofSeconds(30));
        
        // 每次检测的连接数（-1 表示检测所有）
        poolConfig.setNumTestsPerEvictionRun(-1);
        
        // 连接耗尽时是否阻塞等待
        poolConfig.setBlockWhenExhausted(true);
        // 最大等待时间
        poolConfig.setMaxWait(Duration.ofMillis(5000));

        // Jedis 客户端配置
        JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
                .usePooling()
                .poolConfig(poolConfig)
                .and()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();

        JedisConnectionFactory factory = new JedisConnectionFactory(redisConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }
}
