package com.hao.haoaicode.monitor;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;

@Component
public class AppMetricsCollector {

    @Resource
    MeterRegistry meterRegistry;
    /**
     * 记录每个 appId 调用某个 endpoint 的耗时分布。
     * @param endpoint 接口名称，例如：/api/gen
     * @param status 接口状态，例如：success、error
     * @param durationMs 耗时
     */
    public void recordTimeConsumption(String endpoint, String status, long durationMs) {
        Timer.builder("app.time.consumption")
                .tag("endpoint", endpoint != null ? endpoint : "unknown")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
    /**
     * 记录每个 appId 调用某个 endpoint 的请求次数。
     * @param endpoint 接口名称，例如：/api/gen
     * @param status 接口状态，例如：success、error
     */
    public void recordEndpointRequest(String endpoint, String status) {
        Counter.builder("app.endpoint.requests")
                .tag("endpoint", endpoint != null ? endpoint : "unknown")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .increment();
    }
    /**
     * 记录每个 appId 项目生成结果的次数。
     * @param result 项目生成结果，例如：hasFiles、uploadSuccess
     */
    public void recordProjectGenerationResult(String result) {
        Counter.builder("app.project.generation.result")
                .tag("result", result != null ? result : "unknown")
                .register(meterRegistry)
                .increment();
    }
    /**
     * 记录每个 appId 上传到 COS 的耗时分布。
     * @param status 上传状态，例如：success、error
     * @param durationMs 耗时
     */
    public void recordCosUpload(String status, long durationMs) {
        Timer.builder("app.cos.upload.time.consumption")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordHistoricalBatch(String status, int batchSize) {
        Counter.builder("app.historical.batch")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("app.historical.batch.size")
                .tag("status", status != null ? status : "unknown")
                .register(meterRegistry)
                .record(Math.max(batchSize, 0));
    }

    public void recordCodeGenerationPayload(int generatedFileCount, long generatedChars, int mergedFileCount, long mergedChars) {
        DistributionSummary.builder("app.codegen.generated.files")
                .register(meterRegistry)
                .record(Math.max(generatedFileCount, 0));

        DistributionSummary.builder("app.codegen.generated.chars")
                .baseUnit("chars")
                .register(meterRegistry)
                .record(Math.max(generatedChars, 0));

        DistributionSummary.builder("app.codegen.merged.files")
                .register(meterRegistry)
                .record(Math.max(mergedFileCount, 0));

        DistributionSummary.builder("app.codegen.merged.chars")
                .baseUnit("chars")
                .register(meterRegistry)
                .record(Math.max(mergedChars, 0));
    }
}
