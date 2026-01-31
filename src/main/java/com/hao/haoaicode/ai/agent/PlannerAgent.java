package com.hao.haoaicode.ai.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface PlannerAgent {
    /**
     * 计划任务
     * @param userRequirement 用户需求描述
     * @return 计划结果
     */
    @Agent(description = "根据用户需求生成 VUE项目规划（页面列表、功能说明、主题风格等，JSON 格式）")
    String planProject(@UserMessage String userRequirement);
}
