package com.hao.haoaicode.review.model;

import lombok.Data;
import java.util.Map;

/**
 * RAG 检索来源文档
 */
@Data
public class SourceDocument {
    /**
     * 来源类型：vector/graph/web
     */
    private String sourceType;
    
    /**
     * 内容
     */
    private String content;
    
    /**
     * 相关性分数
     */
    private Double score;
    
    /**
     * 元数据
     */
    private Map<String, Object> metadata;
}
