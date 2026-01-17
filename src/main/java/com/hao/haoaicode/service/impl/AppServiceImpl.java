package com.hao.haoaicode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
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
import com.hao.haoaicode.mapper.AppMapper;
import com.hao.haoaicode.model.dto.app.AppAddRequest;
import com.hao.haoaicode.model.dto.app.AppQueryRequest;
import com.hao.haoaicode.model.entity.App;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.model.vo.AppVO;
import com.hao.haoaicode.model.vo.UserVO;
import com.hao.haoaicode.monitor.MonitorContext;
import com.hao.haoaicode.monitor.MonitorContextHolder;
import com.hao.haoaicode.ratelimit.RateLimitType;
import com.hao.haoaicode.ratelimit.annotation.RateLimit;
import com.hao.haoaicode.review.RagEnhancementService;
import com.hao.haoaicode.review.model.CodeAuditResponse;
import com.hao.haoaicode.service.AppService;
import com.hao.haoaicode.service.ChatHistoryService;
import com.hao.haoaicode.service.ScreenshotService;
import com.hao.haoaicode.service.UserService;
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
    private StringRedisTemplate stringRedisTemplate;  // 如果还没有


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
        // 4. 检查是否已有 deployKey
        String deployKey = app.getDeployKey();
        // 没有则生成 6 位 deployKey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，构建源目录路径
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
// 6. 检查源目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
// 7. Vue 项目特殊处理：执行构建
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 将 dist 目录作为部署源
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }
// 8. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;

        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 8. 更新应用的 deployKey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 9. 返回可访问的 URL
        // 10. 构建应用访问 URL
        String appDeployUrl = String.format("%s/%s/", deployHost, deployKey);
        // 11. 异步生成截图并更新应用封面
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
        chatMessageRouter.route(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        // 6. 设置监控上下文
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(loginUser.getId().toString())
                .appId(appId.toString())
                .build());
        // 7. 调用模型生成代码
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId, loginUser);
        // 8. 收集生成的代码，存储到对话历史
        return streamHandlerExecutor.doExecute(codeStream, appId, loginUser, codeGenTypeEnum)
                .doFinally(signalType -> {
                    MonitorContextHolder.clearContext();
                    log.info("代码生成完成，appId: {}, 触发代码审计", appId);
                    
                    // 异步触发代码审计
                    CompletableFuture.runAsync(() -> {
                        try {
                            // 等待一小段时间，确保文件已保存
                            Thread.sleep(500);
                            
                            // 1. 获取生成的代码
                            String generatedCode = getGeneratedCodeFromFile(appId, codeGenTypeEnum);
                            
                            if (StrUtil.isNotBlank(generatedCode)) {
                                // 2. 调用代码审计
                                log.info("开始审计代码，appId: {}, 代码长度: {}", appId, generatedCode.length());
                                CodeAuditResponse auditResult = ragEnhancementService.auditCode(
                                    generatedCode, 
                                    getLanguageFromCodeType(codeGenTypeEnum)
                                );
                                
                                // 3. 处理审计结果
                                if (auditResult != null && auditResult.getAuditResult() != null) {
                                    String riskLevel = auditResult.getAuditResult().getRiskLevel();
                                    Integer score = auditResult.getAuditResult().getSecurityScore();
                                    log.info("代码审计完成，appId: {}, 风险等级: {}, 安全评分: {}", 
                                        appId, riskLevel, score);
                                    
                                    // 4. 保存审计结果到 Redis
                                    saveAuditResult(appId, auditResult);
                                    
                                    // 5. 高危代码告警
                                    if ("HIGH".equals(riskLevel)) {
                                        log.warn("⚠️ 检测到高危代码！appId: {}, 漏洞数: {}", 
                                            appId, 
                                            auditResult.getAuditResult().getVulnerabilities() != null ? 
                                                auditResult.getAuditResult().getVulnerabilities().size() : 0);
                                    }
                                } else {
                                    log.warn("审计结果为空，appId: {}", appId);
                                }
                            } else {
                                log.warn("未找到生成的代码，appId: {}", appId);
                            }
                            
                        } catch (Exception e) {
                            log.error("代码审计失败，appId: {}, error: {}", appId, e.getMessage());
                            // 审计失败不影响主流程
                        }
                    });

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
     * 从 Redis 缓存中获取生成的代码（已废弃，改用文件系统）
     * 
     * @param appId 应用ID
     * @return 生成的代码
     */
    @Deprecated
    private String getGeneratedCode(Long appId) {
        try {
            // 假设代码存储在 Redis 中，key 格式为 "generated_code:{appId}"
            String key = "generated_code:" + appId;
            
            // 使用 StringRedisTemplate 或 RedisTemplate 读取
            // 这里需要注入 RedisTemplate
            String code = stringRedisTemplate.opsForValue().get(key);
            
            if (StrUtil.isNotBlank(code)) {
                log.info("从 Redis 读取到代码，appId: {}, 长度: {}", appId, code.length());
                return code;
            }
            
            log.warn("Redis 中未找到代码，appId: {}", appId);
            return null;
            
        } catch (Exception e) {
            log.error("从 Redis 读取代码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将审计结果保存到 Redis
     * 
     * @param appId 应用ID
     * @param auditResult 审计结果
     */
    private void saveAuditResult(Long appId, CodeAuditResponse auditResult) {
        try {
            String key = "audit_result:" + appId;
            
            // 使用 Jackson 序列化为 JSON
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(auditResult);
            
            // 保存到 Redis，设置过期时间（1 小时）
            stringRedisTemplate.opsForValue().set(key, json, 1, TimeUnit.HOURS);
            
            log.info("审计结果已保存到 Redis，appId: {}, key: {}", appId, key);
            
        } catch (Exception e) {
            log.error("保存审计结果失败: {}", e.getMessage());
        }
    }


}
