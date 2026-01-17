package com.hao.haoaicode.review.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 服务请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRequest {
    /**
     * 查询内容
     */
    private String query;
    
    /**
     * 是否启用向量检索
     */
    @Builder.Default
    private Boolean enableVector = true;
    
    /**
     * 是否启用知识图谱
     */
    @Builder.Default
    private Boolean enableGraph = true;
    
    /**
     * 是否启用网络搜索
     */
    @Builder.Default
    private Boolean enableWeb = false;
    
    /**
     * 返回结果数量
     */
    @Builder.Default
    private Integer topK = 3;
}
