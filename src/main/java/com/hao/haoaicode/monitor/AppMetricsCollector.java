package com.hao.haoaicode.monitor;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import com.hao.haoaicode.ai.tools.ToolManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;

@Component
public class AppMetricsCollector {

    @Resource
    MeterRegistry meterRegistry;



    // 1 接口级监控
    // 记录接口耗时
    public void recordTimeConsumption(String endpoint, String status, long durationMs){
        Timer.builder("app.time.consumption").tag("endpoint", endpoint)
                .tag("status", status)
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // 记录接口的请求次数
    public void recordEndpointRequest(String endpoint, String status){
        Counter.builder("app.count")
            .register(meterRegistry)
            .increment();
    }

    // 2.项目生成结果
    // 在 ProjectGenerationPostProcessor.processGeneration 结束时调用
    public void recordProjectGenerationResult(String result){
        Counter.builder("app.project.generation.result")
            .register(meterRegistry)
            .increment();
    }

    // 3.COS上传耗时 & 失败/成功
    public void recordCosUpload(String status, long durationMs){
        Timer.builder("app.cos.upload.time.consumption")
            .register(meterRegistry);
    }

    // 4.历史刷盘批次
    // 在 ProjectGenerationPostProcessor.processGeneration 结束时调用
    public void recordHistoricalBatch(String status, int batchSize){
        Counter.builder("app.historical.batch")
            .register(meterRegistry)
            .increment();
    }
}
