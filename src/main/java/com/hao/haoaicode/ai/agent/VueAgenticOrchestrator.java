package com.hao.haoaicode.ai.agent;

import com.hao.haoaicode.model.entity.User;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.hao.haoaicode.core.handler.AgenticVueExecutor;

import jakarta.annotation.Resource;
import reactor.core.publisher.Flux;

/**
 * 最小可行的 Vue Agentic 协同 orchestrator
 */
@Component
@Slf4j
public class VueAgenticOrchestrator {
    @Resource
    private ChatModel agenticChatModel;

    @Resource
    private AgenticVueExecutor agenticVueExecutor;

    /**
     * Agentic模式生成vue项目
     * @param userRequirement
     * @param appId
     * @param loginUser
     * @return
     */
    public Flux<String> generateProject(String userRequirement, Long appId, User loginUser){
        PlannerAgent plannerAgent = AgenticServices
                .agentBuilder(PlannerAgent.class)
                .chatModel(agenticChatModel)
                .build();

        ScaffolderAgent scaffolderAgent = AgenticServices
                .agentBuilder(ScaffolderAgent.class)
                .chatModel(agenticChatModel)
                .build();

        return Flux.create(sink -> {
            sink.next("开始 Agentic Vue 项目生成...\n");
            log.info("[Agentic] 开始生成, appId={}", appId);

            String planJson;
            try {
                planJson = plannerAgent.planProject(userRequirement);
            } catch (Exception e) {
                log.error("[Agentic] 规划阶段失败", e);
                sink.next("Agentic 规划失败：" + e.getMessage() + "\n");
                sink.complete();
                return;
            }

            sink.next("项目规划已生成：\n" + planJson + "\n");
            log.info("[Agentic] 规划生成完成, 长度={}", planJson.length());

            String scaffoldSteps;
            try {
                scaffoldSteps = scaffolderAgent.scaffoldProject(planJson);
            } catch (Exception e) {
                log.error("[Agentic] 脚手架初始化失败", e);
                sink.next("Agentic 脚手架初始化失败：" + e.getMessage() + "\n");
                sink.complete();
                return;
            }

            sink.next("脚手架步骤规划完成，开始输出步骤...\n");
            log.info("[Agentic] 脚手架步骤规划返回, 长度={}", scaffoldSteps.length());

            for (String line : scaffoldSteps.split("\\R")) {
                if (line != null && !line.isBlank()) {
                    sink.next(line + "\n");
                }
            }
            
            String sessionId = loginUser.getId() + ":" + appId;
            sink.next("开始执行 Vue 项目代码生成与构建...\n");

            agenticVueExecutor.execute(sessionId, appId, loginUser, userRequirement, planJson, scaffoldSteps)
                    .subscribe(
                            msg -> {
                                if (!sink.isCancelled()) {
                                    sink.next(msg);
                                }
                            },
                            error -> {
                                if (!sink.isCancelled()) {
                                    log.error("[Agentic] 执行阶段失败", error);
                                    sink.next("Agentic 执行阶段失败：" + error.getMessage() + "\n");
                                    sink.complete();
                                }
                            },
                            () -> {
                                if (!sink.isCancelled()) {
                                    sink.complete();
                                }
                            }
                    );
        });
    }
    
}
