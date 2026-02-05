package com.hao.haoaicode.model;

import lombok.Data;

@Data
public class BuildResult {

    private boolean success;
    private String message;
    private String detailLog;   // 可选：构建日志片段（用于报错时展示）

    public static BuildResult ok() {
        BuildResult r = new BuildResult();
        r.success = true;
        r.message = "OK";
        return r;
    }

    public static BuildResult fail(String message, String detailLog) {
        BuildResult r = new BuildResult();
        r.success = false;
        r.message = message;
        r.detailLog = detailLog;
        return r;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getDetailLog() { return detailLog; }
}
