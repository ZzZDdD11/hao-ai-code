package com.hao.haoaicode.benchmark;

import com.hao.haoaicode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天历史写入性能基准测试（高并发灾难模拟版 V2.0）
 * * 关键修改：通过 properties 强制限制连接池大小，确保触发资源耗尽错误
 */
@Slf4j
// 【关键修改点】强制设置 HikariCP 连接池参数：只有 5 个连接，等待超时仅 1 秒
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.minimum-idle=5"
})
public class ChatHistoryBenchmarkTest {

    @Resource
    private ChatHistoryService chatHistoryService;

    // 请确保数据库存在 id=1 的 user 和 app
    private static final Long TEST_APP_ID = 1L;
    private static final Long TEST_USER_ID = 1L;

    @Test
    public void benchmarkConcurrentWrite() throws InterruptedException {
        // ================= 参数升级 =================
        // 模拟 300 个并发用户（远超连接池大小 5）
        int threadCount = 300;
        // 总写入量提升到 50,000
        int writePerThread = 150; // 300 * 150 = 45000 次请求
        int messageSize = 4096; // 4KB 长文本
        // ============================================

        int totalWrites = threadCount * writePerThread;
        String message = generateMessage(messageSize);

        log.info("========== 🔥 高并发灾难模拟测试 V2.0 (限制连接池版) 🔥 ==========");
        log.info("环境限制: 最大连接数=5, 等待超时=1000ms (模拟生产环境数据库瓶颈)");
        log.info("配置参数: 线程数={}, 总任务={}, 消息大小={}KB", threadCount, totalWrites, messageSize / 1024);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < writePerThread; i++) {
                        long writeStart = System.currentTimeMillis();
                        try {
                            chatHistoryService.addChatMessage(
                                    TEST_APP_ID,
                                    message,
                                    "ai",
                                    TEST_USER_ID
                            );
                            long cost = System.currentTimeMillis() - writeStart;
                            successCount.incrementAndGet();
                            totalLatency.addAndGet(cost);
                        } catch (Exception e) {
                            int currentFail = failCount.incrementAndGet();
                            // 采样打印错误，证明确实是连接池爆了
                            if (currentFail % 200 == 0) {
                                log.error("写入失败采样 (第{}次): {}", currentFail, e.getMessage());
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // 计算结果
        double qps = totalWrites * 1000.0 / totalTime;
        double avgLatency = successCount.get() > 0 ? (double) totalLatency.get() / successCount.get() : 0;

        log.info("========== 🏁 测试结束 🏁 ==========");
        log.info("总耗时: {} ms", totalTime);
        log.info("------------------------------------");
        log.info("✅ 成功写入: {} 条", successCount.get());
        // 重点关注这里
        log.info("❌ 失败写入: {} 条", failCount.get());
        log.info("------------------------------------");
        // 修复了日志格式问题
        log.info("📊 QPS (吞吐量): {}", String.format("%.2f", qps));
        log.info("🐢 平均响应耗时: {} ms", String.format("%.2f", avgLatency));

        if (failCount.get() > 0) {
            log.info("💡 结论: 成功复现“灾难”！数据库连接池不堪重负，大量请求超时丢弃。");
        } else {
            log.info("💡 结论: 依然没有失败？你的数据库可能是在内存里跑的（H2？），或者配置未生效。");
        }
        log.info("============================================");
    }

    private String generateMessage(int sizeInBytes) {
        StringBuilder sb = new StringBuilder(sizeInBytes);
        String template = "public void test() { /* Large Code Block */ ";
        while (sb.length() < sizeInBytes) {
            sb.append(template);
        }
        return sb.substring(0, sizeInBytes);
    }
}