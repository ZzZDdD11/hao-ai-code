package com.hao.haoaicode.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class CodeGenerationEvent {
    // 事件类型：code_chunk（代码片段）, audit_result（审计结果）, complete（完成）
    private String type;
    
    // 内容（代码片段或审计结果 JSON）
    private String content;
    
    // 元数据
    private Map<String, Object> metadata;
}