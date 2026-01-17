package com.hao.haoaicode.service;

import org.springframework.stereotype.Service;

@Service
public interface ScreenshotService {

    public String generateAndUploadScreenshot(String webUrl);

}
