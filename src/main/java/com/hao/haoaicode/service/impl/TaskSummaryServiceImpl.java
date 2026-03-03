package com.hao.haoaicode.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.hao.haoaicode.mapper.AppTaskSummaryMapper;
import com.hao.haoaicode.model.context.GenerationContext;
import com.hao.haoaicode.model.entity.AppTaskSummary;
import com.hao.haoaicode.service.TaskSummaryService;
import com.mybatisflex.core.query.QueryWrapper;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class TaskSummaryServiceImpl implements TaskSummaryService {
    @Resource
    private AppTaskSummaryMapper appTaskSummaryMapper;

    @Override
    public void generateAndSaveSummary(GenerationContext ctx) {
        if (ctx == null) {
            return;
        }
        AppTaskSummary summary = new AppTaskSummary();
        summary.setAppId(ctx.getAppId());
        summary.setUserId(ctx.getUserId());
        summary.setCodeGenType(ctx.getCodeGenType());
        summary.setRoundNo(nextRoundNo(ctx.getAppId(), ctx.getCodeGenType()));
        summary.setPrompt(ctx.getUserPrompt());
        if (ctx.isSuccess()) {
            summary.setResultSummary("代码生成成功");
        } else {
            String msg = ctx.getErrorMessage();
            summary.setResultSummary(msg == null || msg.isBlank() ? "代码生成失败" : ("代码生成失败: " + msg));
        }
        summary.setSourceKey(ctx.getSourceKey());
        summary.setVersionId(ctx.getVersionId());
        summary.setDurationMs(ctx.getEndTime() - ctx.getStartTime());
        summary.setSuccess(ctx.isSuccess());
        summary.setErrorMessage(ctx.getErrorMessage());
        summary.setTouchedFiles(toJson(ctx.getTouchedFiles()));
        Date now = new Date();
        summary.setCreatedAt(now);
        summary.setUpdatedAt(now);
        appTaskSummaryMapper.insert(summary);
    }

    private Integer nextRoundNo(Long appId, String codeGenType) {
        if (appId == null || appId <= 0) {
            return 1;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .from(AppTaskSummary.class)
                .eq("app_id", appId)
                .eq("code_gen_type", codeGenType)
                .orderBy("round_no", false)
                .limit(1);
        List<AppTaskSummary> list = appTaskSummaryMapper.selectListByQuery(queryWrapper);
        if (list == null || list.isEmpty() || list.get(0).getRoundNo() == null) {
            return 1;
        }
        Integer current = list.get(0).getRoundNo();
        return current == null ? 1 : current + 1;
    }
    /**
     * 将文件列表转换为 JSON 字符串
     * @param files
     * @return
     */
    private String toJson(List<String> files) {
        // 用你项目里已有的 JSON 工具，比如 Hutool / Jackson
        return files == null ? "[]" : cn.hutool.json.JSONUtil.toJsonStr(files);
    }
    /**
     * 查询最近 n 条任务摘要，按 id 倒序（新到旧）
     */
    @Override
    public List<AppTaskSummary> listRecentSummaries(long appId, int n) {
        // 根据 appId，查询最近 n 条任务摘要，按 id 倒序（新到旧）
        QueryWrapper queryWrapper = QueryWrapper.create()
                .from(AppTaskSummary.class)
                .eq("app_id", appId)
                .orderBy("id", false)
                .limit(n);
        return appTaskSummaryMapper.selectListByQuery(queryWrapper);
    }
    
}
