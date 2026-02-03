package com.hao.haoaicode.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 腾讯云COS配置类
 * 
 * @author yupi
 */
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
@Slf4j
public class CosClientConfig {

    /**
     * 域名
     */
    private String host;

    /**
     * secretId
     */
    private String secretId;

    /**
     * 密钥（注意不要泄露）
     */
    private String secretKey;

    /**
     * 区域
     */
    private String region;

    /**
     * 桶名
     */
    private String bucket;

    @Bean
    public COSClient cosClient() {
        String trimmedSecretId = secretId == null ? null : secretId.trim();
        String trimmedSecretKey = secretKey == null ? null : secretKey.trim();
        String trimmedRegion = region == null ? null : region.trim();

        log.info("COS config loaded: secretId={}, secretIdLen={}, region={}, bucket={}, host={}",
                maskSecretId(trimmedSecretId),
                trimmedSecretId == null ? 0 : trimmedSecretId.length(),
                trimmedRegion,
                bucket,
                host);

        if (trimmedSecretId == null || trimmedSecretId.isBlank()) {
            throw new IllegalStateException("cos.client.secretId is blank");
        }
        if (trimmedSecretKey == null || trimmedSecretKey.isBlank()) {
            throw new IllegalStateException("cos.client.secretKey is blank");
        }
        if (trimmedRegion == null || trimmedRegion.isBlank()) {
            throw new IllegalStateException("cos.client.region is blank");
        }
        if (looksLikePlaceholder(trimmedSecretId) || looksLikePlaceholder(trimmedSecretKey)) {
            throw new IllegalStateException("COS credentials are placeholders (environment variables not resolved)");
        }
        if (containsAnyWhitespace(secretId)) {
            throw new IllegalStateException("cos.client.secretId contains whitespace (copy/paste issue)");
        }
        if (containsAnyWhitespace(secretKey)) {
            throw new IllegalStateException("cos.client.secretKey contains whitespace (copy/paste issue)");
        }

        COSCredentials cred = new BasicCOSCredentials(trimmedSecretId, trimmedSecretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(trimmedRegion));
        return new COSClient(cred, clientConfig);
    }

    private boolean looksLikePlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.startsWith("${") && v.endsWith("}");
    }

    private boolean containsAnyWhitespace(String value) {
        if (value == null) {
            return false;
        }
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private String maskSecretId(String value) {
        if (value == null || value.isBlank()) {
            return "(blank)";
        }
        String v = value.trim();
        if (v.length() <= 8) {
            return v.substring(0, 1) + "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }
}
