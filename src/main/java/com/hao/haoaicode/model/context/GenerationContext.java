package com.hao.haoaicode.model.context;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
@Data
public class GenerationContext {
    private Long appId;
    private Long userId;
    private String codeGenType;
    private String userPrompt;
    private long startTime;
    private long endTime;

    private String sourceKey;
    private String versionId;
    // 本次代码生成涉及的文件路径列表
    private List<String> touchedFiles = new ArrayList<>();

    private boolean success;
    private String errorMessage;

    public void addTouchedFile(String path) {
        if (path != null && !path.isBlank()) {
            this.touchedFiles.add(path);
        }
    }
}
