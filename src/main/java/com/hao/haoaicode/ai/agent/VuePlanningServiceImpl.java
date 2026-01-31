package com.hao.haoaicode.ai.agent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VuePlanningServiceImpl implements VuePlanningService {

    @Resource
    private ChatModel agenticChatModel;

    @Override
    public VuePlanContext plan(String userRequirement) {
        PlannerAgent plannerAgent = AgenticServices
                .agentBuilder(PlannerAgent.class)
                .chatModel(agenticChatModel)
                .build();

        ScaffolderAgent scaffolderAgent = AgenticServices
                .agentBuilder(ScaffolderAgent.class)
                .chatModel(agenticChatModel)
                .build();

        VuePlanContext context = new VuePlanContext();
        context.setUserRequirement(userRequirement);

        String planJson = plannerAgent.planProject(userRequirement);
        context.setPlanJson(planJson);

        String scaffoldSteps = scaffolderAgent.scaffoldProject(planJson);
        context.setScaffoldSteps(scaffoldSteps);

        return context;
    }
}

