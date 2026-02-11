package com.hao.haoaicode.service;

import java.util.Map;

public interface ProjectGenerationPostProcessor {

    /**
     * 把 AI 多文件响应解析为 filePath -> fileContent 的 Map。
     */
    Map<String, String> parseMultiFileProtocol(String text);

    /**
     * Full post-processing pipeline for a generation result of the given app:
     * parse multi-file protocol, cache files in-memory for preview and upload to COS.
     */
    ProjectGenerationResult processGeneration(long appId, String aiResponse);

    /**
     * 获取当前应用的所有生成文件（ filePath -> fileContent）
     * @param appId
     * @return
     */
    Map<String, String> getGeneratedFiles(long appId);

    /**
     * 获取当前应用的单个生成文件内容（ filePath -> fileContent）
     * @param appId
     * @param filePath
     * @return
     */
    String getGeneratedFileContent(long appId, String filePath);

    /**
     * 清除当前应用的所有生成文件缓存
     * @param appId
     */
    void clearGeneratedFiles(long appId);

    /**
     * 上传当前应用的所有生成文件到 COS
     * @param appId
     * @return
     */
    boolean uploadToCos(long appId);

    /**
     * 项目生成结果
     */
    class ProjectGenerationResult {
        /**
         * 是否有文件生成
         */
        private final boolean hasFiles;
        /**
         * 是否上传成功
         */ 
        private final boolean uploadSuccess;

        public ProjectGenerationResult(boolean hasFiles, boolean uploadSuccess) {
            this.hasFiles = hasFiles;
            this.uploadSuccess = uploadSuccess;
        }

        public boolean hasFiles() {
            return hasFiles;
        }

        public boolean isUploadSuccess() {
            return uploadSuccess;
        }
    }
}
