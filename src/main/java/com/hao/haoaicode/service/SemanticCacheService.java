package com.hao.haoaicode.service;

import java.util.List;

import com.hao.haoaicode.model.SemanticCacheResult;
import com.hao.haoaicode.model.entity.AppCodeVersion;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.model.vo.AppCodeVersionVO;

public interface SemanticCacheService {
    // 判断prompt是否已有相似度高的缓存,如果有,则返回缓存的代码位置
    SemanticCacheResult checkPrompt(String prompt, Long appId, CodeGenTypeEnum codegenType);

    void savaCache(String prompt, Long appId, Long userId, CodeGenTypeEnum codegenType, String codeLocation, double embeddingScore);

    // 获取应用的所有代码版本
    List<AppCodeVersionVO> getAppVersions(Long appId);
}
