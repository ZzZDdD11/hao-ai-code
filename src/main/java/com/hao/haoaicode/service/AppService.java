package com.hao.haoaicode.service;

import com.hao.haoaicode.model.dto.app.AppAddRequest;
import com.hao.haoaicode.model.dto.app.AppQueryRequest;
import com.hao.haoaicode.model.entity.App;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.vo.AppVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author hao
 */
public interface AppService extends IService<App> {
    AppVO getAppVO(App app);

    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 部署应用
     * @param appId
     * @param loginUser
     * @return
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 生成应用
     * @param appId
     * @param message
     * @param loginUser
     * @return
     */
     Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    public void generateAppScreenshotAsync(Long appId, String appUrl);

    public Long createApp(AppAddRequest appAddRequest, User loginUser);

    }
