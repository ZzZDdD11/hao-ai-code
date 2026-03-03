package com.hao.haoaicode.service;

import com.hao.haoaicode.model.context.GenerationContext;
import com.hao.haoaicode.model.entity.AppTaskSummary;

import java.util.List;

public interface TaskSummaryService {
    /**
     * 生成并保存任务摘要
     *
     * @param ctx 生成上下文，包含任务相关信息
     */
    void generateAndSaveSummary(GenerationContext ctx);
    /**
     * 列出最近 n 轮的任务摘要
     * @param appId 应用 ID
     * @param n     条数
     * @return 最近 n 条任务摘要列表
     */
    List<AppTaskSummary> listRecentSummaries(long appId, int n);
}
