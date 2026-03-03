package com.hao.haoaicode.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.util.Date;

@Data
@Table("app_task_summary")
public class AppTaskSummary {

    @Id
    private Long id;

    private Long appId;
    private Long userId;
    private String codeGenType;
    private Integer roundNo;

    private String prompt;
    private String resultSummary;
    private String touchedFiles;

    private String sourceKey;
    private String versionId;

    private Long durationMs;
    private Boolean success;
    private String errorMessage;

    private Date createdAt;
    private Date updatedAt;
}