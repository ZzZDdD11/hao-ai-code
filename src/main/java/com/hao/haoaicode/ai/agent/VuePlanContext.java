package com.hao.haoaicode.ai.agent;

import lombok.Data;

@Data
public class VuePlanContext {

    private String userRequirement;

    private String planJson;

    private String scaffoldSteps;
}

