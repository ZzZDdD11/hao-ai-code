package com.hao.haoaicode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.hao.haoaicode.model.SemanticCacheResult;
import com.hao.haoaicode.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.haoaicode.ai.AiCodeGenTypeRoutingService;
import com.hao.haoaicode.ai.AiCodeGenTypeRoutingServiceFactory;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.core.AiCodeGeneratorFacade;
import com.hao.haoaicode.core.builder.VueProjectBuilder;
import com.hao.haoaicode.core.handler.StreamHandlerExecutor;
import com.hao.haoaicode.exception.BusinessException;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.exception.ThrowUtils;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.mapper.AppMapper;
import com.hao.haoaicode.model.BuildResult;
import com.hao.haoaicode.model.dto.app.AppAddRequest;
import com.hao.haoaicode.model.dto.app.AppQueryRequest;
import com.hao.haoaicode.model.entity.App;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.model.vo.AppVO;
import com.hao.haoaicode.model.vo.UserVO;
import com.hao.haoaicode.monitor.AppMetricsCollector;
import com.hao.haoaicode.monitor.MonitorContext;
import com.hao.haoaicode.monitor.MonitorContextHolder;
import com.hao.haoaicode.ratelimit.RateLimitType;
import com.hao.haoaicode.ratelimit.annotation.RateLimit;
import com.hao.haoaicode.review.RagEnhancementService;
import com.hao.haoaicode.review.model.CodeAuditResponse;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * 应用 服务层实现。
 *
 * @author hao
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService {
    @Value("${code.deploy-host:http://localhost}")
    private String deployHost;
    @Autowired
    private UserService userService;
    @Autowired
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
    @Autowired
    private ChatHistoryService chatHistoryService;
    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;
    @Resource
    private VueProjectBuilder vueProjectBuilder;
    @Resource
    private ScreenshotService screenshotService;
    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;
    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;
    @Resource
    private ChatMessageRouter chatMessageRouter;
    @Resource
    private RagEnhancementService ragEnhancementService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CosManager cosManager;
    @Resource
    private ConversationHistoryRecorder conversationHistoryRecorder;
    @Resource
    private BuildClient buildClient;
    @Resource
    private MeterRegistry meterRegistry;
    @Resource
    private AppMetricsCollector appMetricsCollector;
    @Resource
    private SemanticCacheService semanticCacheService;
    @Value("${code.deploy-cos-prefix:/deploy}")
    private String deployCosPrefix;


    /**
     * 查询应用信息
     * @param app
     * @return
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    /**
     * 分页查询应用
     * @param appQueryRequest
     * @return
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }



    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    /**
     * 进行应用部署
     * @param appId
     * @param loginUser
     * @return
     */
    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 生成 deployKey
        // 注意：COS 覆盖写同名对象（同一路径）在短时间内可能读到旧版本（表现为首次打开下载、第二次正常）。
        // 因此对 VUE_PROJECT 每次部署都生成新的 deployKey，避免覆盖写。
        String deployKey = app.getDeployKey();

        // 5. 获取代码生成类型
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");

        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // 1. 从 Redis 获取最新源码目录 key
            String normalizedBaseKey = stringRedisTemplate.opsForValue()
                    .get(String.format("code:source:latest:%d", appId));
            if (StrUtil.isBlank(normalizedBaseKey)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
            }

            // 2. 每次部署生成新的 deployKey，避免 COS 覆盖导致读到旧版本
            deployKey = RandomUtil.randomString(8);

            // 3. 调用构建服务构建 Vue 项目并上传 dist 到 COS
            BuildResult buildResult = buildClient.buildVueProject(appId, normalizedBaseKey, deployKey);
            if (buildResult == null || !buildResult.isSuccess()) {
                String baseMsg = (buildResult != null && StrUtil.isNotBlank(buildResult.getMessage()))
                        ? buildResult.getMessage()
                        : "构建服务调用失败";
                String detail = buildResult != null ? buildResult.getDetailLog() : null;

                if (StrUtil.isNotBlank(detail)) {
                    int maxLen = 4000;
                    if (detail.length() > maxLen) {
                        detail = detail.substring(0, maxLen) + "\n...(truncated)";
                    }
                    throw new BusinessException(
                            ErrorCode.SYSTEM_ERROR,
                            "Vue 项目构建失败，请检查代码和依赖\n\n" + baseMsg + "\n\n" + detail
                    );
                }
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "Vue 项目构建失败，请检查代码和依赖\n\n" + baseMsg
                );
            }

            // 4. 构建成功，更新应用部署信息
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setDeployKey(deployKey);
            updateApp.setDeployedTime(LocalDateTime.now());
            boolean updateResult = this.updateById(updateApp);
            ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");

            String appDeployUrl = "/static/" + deployKey + "/index.html";
            //generateAppScreenshotAsync(appId, appDeployUrl);
            return appDeployUrl;
        }

        // 非 VUE_PROJECT 类型：仍然基于本地源码目录部署
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }

        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }

        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");

        String appDeployUrl = String.format("%s/%s/", deployHost, deployKey);
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;

    }

    /**
     * 调用门面类生成应用代码
     * @param appId
     * @param message
     * @param loginUser
     * @return
     */
    @Override
    @RateLimit(key = "chat", rate = 5, rateInterval = 60, limitType = RateLimitType.USER,
            message = "每分钟最多发送5条消息")
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // start 记录请求开始时间
        long startTime = System.currentTimeMillis();
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        
        // 5. 将用户消息存储到对话历史
        conversationHistoryRecorder.recordUserMessage(appId, message, loginUser.getId());
        // 6. 设置监控上下文
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(loginUser.getId().toString())
                .appId(appId.toString())
                .build());
        // 检查积分，扣除积分
//        // 检查prompt是否重复，若重复且阈值大于0.9可直接复用代码
//        SemanticCacheResult checkPrompt = semanticCacheService.checkPrompt(message, appId, codeGenTypeEnum);
//        if(checkPrompt.isHit() && checkPrompt.getScore() >= 0.9){
//            // 若命中，直接返回缓存的代码位置
//            return Flux.just(checkPrompt.getCodeLocation());
//        }else if(checkPrompt.isHit() && checkPrompt.getScore() >= 0.8){
//            // 若命中，且相似度大于0.8，采取上下文策略
//
//        }
        // 7. 调用模型生成代码
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId, loginUser);
        // 8. 收集生成的代码，进行处理并存储到对话历史
        return codeStream.doFinally(signalType -> {
                long endTime = System.currentTimeMillis();

                //  Reactor 的 SignalType 映射 status 值
                String status;
                switch (signalType) {
                    case ON_COMPLETE:
                        status = "success";
                        break;
                    case ON_ERROR:
                        status = "error";
                        break;
                    case CANCEL:
                        status = "cancel"; // 看你要不要单独区分，不需要就也当 error
                        break;
                    default:
                        status = "other";
                }
                long durationMs = endTime - startTime;
                // 记录接口耗时
                appMetricsCollector.recordTimeConsumption("chatToGenCode", status, durationMs);


                    MonitorContextHolder.clearContext();

                    log.info("代码生成完成，appId: {}, 触发代码质量检查", appId);
                    
//                    // 异步触发代码质量检查
//                    CompletableFuture.runAsync(() -> {
//                        try {
//                            // 等待一小段时间，确保文件已保存
//                            Thread.sleep(500);
//
//                            // 1. 获取生成的代码
//                            String generatedCode = getGeneratedCodeFromFile(appId, codeGenTypeEnum);
//
//                            if (StrUtil.isNotBlank(generatedCode)) {
//                                // 2. 调用代码质量检查
//                                log.info("开始质量检查代码，appId: {}, 代码长度: {}", appId, generatedCode.length());
//                                CodeAuditResponse qualityResult = ragEnhancementService.checkCodeQuality(
//                                    generatedCode,
//                                    getLanguageFromCodeType(codeGenTypeEnum)
//                                );
//
//                                // 3. 处理质量检查结果
//                                if (qualityResult != null && qualityResult.getAuditResult() != null) {
//                                    String riskLevel = qualityResult.getAuditResult().getRiskLevel();
//                                    Integer score = qualityResult.getAuditResult().getSecurityScore();
//                                    log.info("代码质量检查完成，appId: {}, 风险等级: {}, 质量评分: {}",
//                                        appId, riskLevel, score);
//
//                                    // 4. 保存质量检查结果到 Redis
//                                    saveAuditResult(appId, qualityResult);
//
//                                    // 5. 高危代码告警
//                                    if ("HIGH".equals(riskLevel)) {
//                                        log.warn("⚠️ 检测到高危规范问题！appId: {}, 问题数: {}",
//                                            appId,
//                                            qualityResult.getAuditResult().getVulnerabilities() != null ?
//                                                qualityResult.getAuditResult().getVulnerabilities().size() : 0);
//                                    }
//                                } else {
//                                    log.warn("质量检查结果为空，appId: {}", appId);
//                                }
//                            } else {
//                                log.warn("未找到生成的代码，appId: {}", appId);
//                            }
//
//                        } catch (Exception e) {
//                            log.error("代码质量检查失败，appId: {}, error: {}", appId, e.getMessage());
//                            // 质量检查失败不影响主流程
//                        }
//                    });

                });

    }
    /**
     * 删除应用时关联删除对话历史
     *
     * @param id 应用ID
     * @return 是否成功
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        // 转换为 Long 类型
        Long appId = Long.valueOf(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史
        try {
            chatHistoryService.deleteChatMessage(appId);
        } catch (Exception e) {
            // 记录日志但不阻止应用删除
            log.error("删除应用关联对话历史失败: {}", e.getMessage());
        }
        // 删除应用
        return super.removeById(id);
    }

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新应用封面字段
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(screenshotUrl);
            boolean updated = this.updateById(updateApp);
            ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
        });
    }

    private String normalizeCosDirKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        while (k.endsWith("/")) {
            k = k.substring(0, k.length() - 1);
        }
        return k + "/";
    }

    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 使用 AI 智能选择代码生成类型
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 插入数据库
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }

    /**
     * 从文件系统获取生成的代码
     * 
     * @param appId 应用ID
     * @param codeGenTypeEnum 代码生成类型
     * @return 生成的代码
     */
    private String getGeneratedCodeFromFile(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        try {
            // 根据代码类型构建文件路径
            String dirName = codeGenTypeEnum.getValue() + "_" + appId;
            String dirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + dirName;
            
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                log.warn("代码目录不存在: {}", dirPath);
                return null;
            }
            
            // 读取主文件内容
            String mainFileName = getMainFileName(codeGenTypeEnum);
            File mainFile = new File(dir, mainFileName);
            
            if (mainFile.exists() && mainFile.isFile()) {
                String code = FileUtil.readUtf8String(mainFile);
                log.info("读取到代码文件: {}, 长度: {}", mainFile.getAbsolutePath(), code.length());
                return code;
            } else {
                log.warn("主文件不存在: {}", mainFile.getAbsolutePath());
                return null;
            }
            
        } catch (Exception e) {
            log.error("读取代码文件失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 根据代码类型获取主文件名
     * 
     * @param codeGenTypeEnum 代码生成类型
     * @return 主文件名
     */
    private String getMainFileName(CodeGenTypeEnum codeGenTypeEnum) {
        return switch (codeGenTypeEnum) {
            case HTML -> "index.html";
            case MULTI_FILE -> "App.java";
            case VUE_PROJECT -> "src/App.vue";
            default -> "index.html";
        };
    }
    
    /**
     * 根据代码类型获取编程语言
     * 
     * @param codeGenTypeEnum 代码生成类型
     * @return 编程语言
     */
    private String getLanguageFromCodeType(CodeGenTypeEnum codeGenTypeEnum) {
        return switch (codeGenTypeEnum) {
            case HTML -> "html";
            case MULTI_FILE -> "java";
            case VUE_PROJECT -> "javascript";
            default -> "java";
        };
    }


    /**
     * 将质量检查结果保存到 Redis
     * 
     * @param appId 应用ID
     * @param auditResult 质量检查结果
     */
    private void saveAuditResult(Long appId, CodeAuditResponse auditResult) {
        try {
            String key = "audit_result:" + appId;
            
            // 使用 Jackson 序列化为 JSON
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(auditResult);
            
            // 保存到 Redis，设置过期时间（1 小时）
            stringRedisTemplate.opsForValue().set(key, json, 1, TimeUnit.HOURS);
            
            log.info("质量检查结果已保存到 Redis，appId: {}, key: {}", appId, key);
            
        } catch (Exception e) {
            log.error("保存质量检查结果失败: {}", e.getMessage());
        }
    }


}
