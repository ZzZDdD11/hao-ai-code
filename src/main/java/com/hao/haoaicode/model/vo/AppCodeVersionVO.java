package com.hao.haoaicode.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AppCodeVersionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 版本记录 id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 应用 id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long appId;

    /**
     * 生成该版本的用户 id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 生成该版本的用户名称（可选，用于列表展示）
     */
    private String userName;

    /**
     * 代码生成类型（原始枚举值，如：VUE_PROJECT / MULTI_FILE）
     */
    private String codeGenType;

    /**
     * 代码生成类型展示名（如：Vue 项目、多文件工程）
     */
    private String codeGenTypeName;

    /**
     * 本次 Prompt 全量内容（用于详情或展开查看）
     */
    private String prompt;

    /**
     * Prompt 摘要（列表展示用，通常截取前若干字符）
     */
    private String promptSummary;

    /**
     * 源码所在位置（COS 源码目录 key，例如：/source-code/{appId}/{timestamp}/）
     */
    private String codeLocation;

    /**
     * 部署 key（如果该版本已构建并部署）
     */
    private String deployKey;

    /**
     * 部署状态：0-未部署，1-已部署，2-部署失败（可根据你的编码习惯调整）
     */
    private Integer deployStatus;

    /**
     * 部署状态展示名（未部署 / 已部署 / 部署失败）
     */
    private String deployStatusName;

    /**
     * 是否当前生效版本
     */
    private Boolean current;

    /**
     * 生成时间（版本创建时间）
     */
    private LocalDateTime createTime;
}