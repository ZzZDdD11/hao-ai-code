package com.hao.haoaicode.review.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码审计请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeAuditRequest {
    /**
     * 待审计的代码
     */
    private String code;
    
    /**
     * 代码语言（java, python, javascript 等）
     */
    @Builder.Default
    private String language = "java";
    
    /**
     * 是否启用图谱分析
     */
    @Builder.Default
    private Boolean enableGraph = true;
}
