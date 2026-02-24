package com.hao.haoaicode.service.impl;

import com.hao.haoaicode.mapper.AppCodeVersionMapper;
import com.hao.haoaicode.model.SemanticCacheResult;
import com.hao.haoaicode.model.entity.AppCodeVersion;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.model.vo.AppCodeVersionVO;
import com.hao.haoaicode.service.SemanticCacheService;
import com.mybatisflex.core.query.QueryOrderBy;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class SemanticCacheServiceImpl implements SemanticCacheService {

    @Resource
    private AppCodeVersionMapper appCodeVersionMapper;

    @Override
    public SemanticCacheResult checkPrompt(String prompt, Long appId, CodeGenTypeEnum codegenType) {
        if (prompt == null || appId == null) {
            return null;
        }
        return null;
    }
    /**
     * 保存语义缓存
     * @param prompt
     * @param appId
     * @param codegenType
     * @param codeLocation
     * @param embedding
     */
    @Override
    public void savaCache(String prompt,
                          Long appId,
                          Long userId,
                          CodeGenTypeEnum codegenType,
                          String codeLocation,
                          double embedding) {
        if (prompt == null || appId == null || codeLocation == null) {
            return;
        }
        try {
            AppCodeVersion updateEntity = new AppCodeVersion();
            updateEntity.setIsCurrent(0);
            QueryWrapper wrapper = QueryWrapper.create()
                    .eq(AppCodeVersion::getAppId, appId);
            appCodeVersionMapper.updateByQuery(updateEntity, wrapper);
        } catch (Exception e) {
            log.warn("重置 app 当前版本标记失败 appId={}, error={}", appId, e.getMessage());
        }
        AppCodeVersion record = AppCodeVersion.builder()
                .appId(appId)
                .userId(userId)
                .codeGenType(codegenType != null ? codegenType.getValue() : null)
                .prompt(prompt)
                .promptEmbedding(null)
                .codeLocation(codeLocation)
                .deployKey(null)
                .deployStatus(0)
                .modelName(null)
                .score(embedding)
                .isCurrent(1)
                .createTime(LocalDateTime.now())
                .build();
        appCodeVersionMapper.insert(record);
        log.info("记录语义缓存并新增版本记录 appId={}, type={}, location={}", appId,
                codegenType != null ? codegenType.getValue() : null,
                codeLocation);
    }


    @Override
    public List<AppCodeVersionVO> getAppVersions(Long appId) {
        if (appId == null || appId <= 0) {
        return List.of();
}
        QueryWrapper wrapper = QueryWrapper.create()
                .eq(AppCodeVersion::getAppId, appId)
                .orderBy(AppCodeVersion::getCreateTime, false);
        List<AppCodeVersion> versions = appCodeVersionMapper.selectListByQuery(wrapper);
        List<AppCodeVersionVO> voList = versions.stream().map(v -> {
        AppCodeVersionVO vo = new AppCodeVersionVO();
        vo.setId(v.getId());
        vo.setAppId(v.getAppId());
        vo.setUserId(v.getUserId());
        vo.setCodeGenType(v.getCodeGenType());
        vo.setPrompt(v.getPrompt());
        // 简单做一个摘要（防止太长）
        String prompt = v.getPrompt();
        if (prompt != null && prompt.length() > 50) {
            vo.setPromptSummary(prompt.substring(0, 50) + "...");
        } else {
            vo.setPromptSummary(prompt);
        }
        vo.setCodeLocation(v.getCodeLocation());
        vo.setDeployKey(v.getDeployKey());
        vo.setDeployStatus(v.getDeployStatus());
        vo.setCurrent(v.getIsCurrent() != null && v.getIsCurrent() == 1);
        vo.setCreateTime(v.getCreateTime());
        // deployStatusName / codeGenTypeName / userName 可以在这里补充
        return vo;
        }).toList();

        return voList;
    }


}
