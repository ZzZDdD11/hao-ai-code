package com.hao.haoaicode.model.entity;

import java.util.List;

import com.hao.haoaicode.model.enums.CodeGenTypeEnum;

import lombok.Data;

@Data
public class TaskSummary {
    private Long appId;
    private Long userId;
    private String codeGenType;
    private String prompt;
    private String touchedFiles;
    private String sourceKey;
    private String versionId;
    private Long durationMs;
    private Boolean success;
    private String errorMessage;
}
