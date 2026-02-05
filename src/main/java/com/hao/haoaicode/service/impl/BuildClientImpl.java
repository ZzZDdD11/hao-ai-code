package com.hao.haoaicode.service.impl;

import com.hao.haoaicode.model.BuildResult;
import com.hao.haoaicode.service.BuildClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class BuildClientImpl implements BuildClient {

    @Value("${build.service.base-url:http://localhost:8002}")
    private String buildServiceBaseUrl;

    private final RestTemplate restTemplate;

    public BuildClientImpl(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofMinutes(10))
                .build();
    }

    @Override
    public BuildResult buildVueProject(Long appId, String sourceKey, String deployKey) {
        String url = buildServiceBaseUrl + "/build/vue";
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("appId", appId);
            requestBody.put("sourceKey", sourceKey);
            requestBody.put("deployKey", deployKey);

            log.info("调用构建服务，url: {}, appId: {}, sourceKey: {}, deployKey: {}", url, appId, sourceKey, deployKey);

            ResponseEntity<BuildResult> response = restTemplate.postForEntity(url, requestBody, BuildResult.class);
            BuildResult result = response.getBody();
            if (result == null) {
                log.error("构建服务返回空结果，status: {}", response.getStatusCode());
                return BuildResult.fail("构建服务未返回结果，HTTP 状态码: " + response.getStatusCode(), null);
            }
            log.info("构建服务调用完成，success: {}, message: {}", result.isSuccess(), result.getMessage());
            return result;
        } catch (Exception e) {
            log.error("调用构建服务异常，url: {}, appId: {}, error: {}", url, appId, e.getMessage());
            return BuildResult.fail("调用构建服务异常: " + e.getMessage(), null);
        }
    }
}
