package com.hao.haoaicode.review.model;

import lombok.Data;
import java.util.List;

/**
 * RAG 服务响应对象
 */
@Data
public class RagResponse {
    /**
     * 最终答案
     */
    private String answer;
    
    /**
     * 来源列表
     */
    private List<SourceDocument> sources;
    
    /**
     * 耗时（秒）
     */
    private Double latency;
    
    /**
     * 推理过程
     */
    private List<String> reasoningTrace;
}
