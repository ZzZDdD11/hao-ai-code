package com.hao.haoaicode.service;

import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {
    public void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}
