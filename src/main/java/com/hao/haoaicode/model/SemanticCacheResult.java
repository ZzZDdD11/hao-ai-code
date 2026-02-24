package com.hao.haoaicode.model;

import lombok.Data;

@Data
public class SemanticCacheResult {
    // 是否命中
    private boolean hit;
    // 相似度分数
    private long score;
    // 代码位置
    private String codeLocation;
}
