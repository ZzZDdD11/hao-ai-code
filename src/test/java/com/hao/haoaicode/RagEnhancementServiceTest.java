package com.hao.haoaicode.review;

import com.hao.haoaicode.review.model.RagResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

/**
 * RAG 增强服务测试
 */
@Slf4j
@SpringBootTest
class RagEnhancementServiceTest {

    @Resource
    private RagEnhancementService ragEnhancementService;

    @Test
    void testCallRagApi() {
        // 测试调用 RAG API
        RagResponse response = ragEnhancementService.callRagApi(
                "XSS 攻击防护",
                false,  // 暂时关闭向量检索（因为还没导入数据）
                false   // 暂时关闭图谱检索
        );

        if (response != null) {
            log.info("✅ RAG API 调用成功");
            log.info("答案: {}", response.getAnswer());
            log.info("耗时: {}s", response.getLatency());
        } else {
            log.warn("⚠️ RAG API 调用失败或返回 null");
        }
    }

    @Test
    void testRetrieveSecurityDocs() {
        // 测试检索安全文档
        String docs = ragEnhancementService.retrieveSecurityDocs("html");

        log.info("检索到的文档:");
        log.info(docs);
    }
}
