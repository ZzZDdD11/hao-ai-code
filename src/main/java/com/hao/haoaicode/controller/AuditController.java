package com.hao.haoaicode.controller;

import com.hao.haoaicode.common.BaseResponse;
import com.hao.haoaicode.common.ResultUtils;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.exception.ThrowUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码审计结果接口
 */
@Slf4j
@RestController
@RequestMapping("/audit")
public class AuditController {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * 获取代码审计结果
     * 
     * @param appId 应用ID
     * @return 审计结果 JSON
     */
    @GetMapping("/result")
    public BaseResponse<String> getAuditResult(@RequestParam Long appId) {
        log.info("获取审计结果，appId: {}", appId);
        
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "appId 不能为空");
        
        String key = "audit_result:" + appId;
        String result = stringRedisTemplate.opsForValue().get(key);
        
        if (result != null) {
            log.info("找到审计结果，appId: {}", appId);
            return ResultUtils.success(result);
        } else {
            log.info("未找到审计结果，appId: {}", appId);
            return ResultUtils.success(null);
        }
    }
}
