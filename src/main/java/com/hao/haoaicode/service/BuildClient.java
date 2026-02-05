package com.hao.haoaicode.service;

import com.hao.haoaicode.model.BuildResult;

public interface BuildClient {
        /**
     * 构建 Vue 项目并上传 dist 到 COS
     *
     * @param appId     应用 ID（用于日志 / 追踪）
     * @param sourceKey 源码在 COS 上的目录 key（例如：/source-code/123/1700000000000/）
     * @param deployKey 部署 key（例如：app_123_1700000000000）
     * @return 构建结果
     */
    BuildResult buildVueProject(Long appId, String sourceKey, String deployKey);
    
}
