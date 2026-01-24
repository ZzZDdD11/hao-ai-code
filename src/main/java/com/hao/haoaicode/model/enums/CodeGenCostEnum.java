
package com.hao.haoaicode.model.enums;

import lombok.Getter;

/**
 * 代码生成消耗积分枚举
 */
@Getter
public enum CodeGenCostEnum {
    HTML(10, "HTML单文件生成"),
    MULTI_FILE(20, "多文件项目生成"),
    VUE_PROJECT(50, "Vue完整项目生成");

    private final int cost;
    private final String description;

    CodeGenCostEnum(int cost, String description) {
        this.cost = cost;
        this.description = description;
    }

    /**
     * 根据 CodeGenTypeEnum 获取消耗积分
     */
    public static int getCost(CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case HTML -> HTML.cost;
            case MULTI_FILE -> MULTI_FILE.cost;
            case VUE_PROJECT -> VUE_PROJECT.cost;
            default -> 10; // 默认10积分
        };
    }
}
