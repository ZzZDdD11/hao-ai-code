package com.hao.haoaicode.core;

import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.AiCodeGeneratorService;
import com.hao.haoaicode.ai.model.HtmlCodeResult;
import com.hao.haoaicode.ai.model.MultiFileCodeResult;
import com.hao.haoaicode.ai.AiCodeGeneratorServiceFactory;
import com.hao.haoaicode.ai.agent.VueAgenticOrchestrator;
import com.hao.haoaicode.ai.agent.VuePlanContext;
import com.hao.haoaicode.ai.agent.VuePlanningService;
import com.hao.haoaicode.core.handler.StreamHandlerExecutor;
import com.hao.haoaicode.exception.BusinessException;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.CodeGenCostEnum;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.saver.CodeFileSaverExecutor;
import com.hao.haoaicode.service.UserWalletService;

import dev.langchain4j.service.TokenStream;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.Map;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;
    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;
    @Resource
    private UserWalletService userWalletService;
    @Resource
    private VueAgenticOrchestrator vueAgenticOrchestrator;
    @Resource
    private VuePlanningService vuePlanningService;

    /**
     * 构建会话 ID
     * 格式：userId:appId
     *
     * @param userId 用户ID
     * @param appId  应用ID
     * @return 会话ID
     */
    private String buildSessionId(Long userId, Long appId) {
        return userId + ":" + appId;
    }



    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, User loginUser) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }


        // ========== 新增：扣费逻辑 ==========
        // 1. 计算消耗积分
        int cost = CodeGenCostEnum.getCost(codeGenTypeEnum);
        
        // 2. 预扣费
        boolean deductSuccess = userWalletService.tryDeduct(loginUser.getId(), cost);
        if (!deductSuccess) {
            log.warn("积分不足, userId: {}, 需要: {}", loginUser.getId(), cost);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, 
                "积分不足，当前操作需要 " + cost + " 积分，请先充值");
        }
        
        log.info("预扣费成功, userId: {}, cost: {}, type: {}", 
            loginUser.getId(), cost, codeGenTypeEnum.getValue());
        // ====================================
        
        try {
            // 3. 构建 sessionId
            String sessionId = buildSessionId(loginUser.getId(), appId);
            
            // 4. 获取服务并生成代码
            AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getService(codeGenTypeEnum);
            
            File result = switch (codeGenTypeEnum) {
                case HTML -> {
                    HtmlCodeResult htmlResult = service.generateHtmlCode(sessionId, userMessage);
                    yield CodeFileSaverExecutor.executeSaver(htmlResult, CodeGenTypeEnum.HTML, appId);
                }
                case MULTI_FILE -> {
                    MultiFileCodeResult multiResult = service.generateMultiFileCode(sessionId, userMessage);
                    yield CodeFileSaverExecutor.executeSaver(multiResult, CodeGenTypeEnum.MULTI_FILE, appId);
                }
                default -> {
                    String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
                }
            };
            
            // 5. 生成成功，记录日志（积分已在Redis扣除，异步落盘到MySQL）
            log.info("代码生成成功, userId: {}, appId: {}, cost: {}, type: {}", 
                loginUser.getId(), appId, cost, codeGenTypeEnum.getValue());
            
            return result;
            
        } catch (Exception e) {
            // 6. 生成失败，回滚积分
            log.error("代码生成失败，回滚积分, userId: {}, cost: {}", loginUser.getId(), cost, e);
            userWalletService.rollback(loginUser.getId(), cost);
            throw e;
        }

    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "generateFallback")
    @Retry(name = "aiService")
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, User loginUser) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
//                // ========== 新增：扣费逻辑 ==========
//        // 1. 计算消耗积分
//        int cost = CodeGenCostEnum.getCost(codeGenTypeEnum);
//
//        // 2. 预扣费
//        boolean deductSuccess = userWalletService.tryDeduct(loginUser.getId(), cost);
//        if (!deductSuccess) {
//            log.warn("积分不足, userId: {}, 需要: {}", loginUser.getId(), cost);
//            return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR,
//                "积分不足，当前操作需要 " + cost + " 积分，请先充值"));
//        }
//
//        log.info("预扣费成功（流式）, userId: {}, cost: {}, type: {}",
//            loginUser.getId(), cost, codeGenTypeEnum.getValue());
//        // ====================================
//
        
        // 立即返回初始消息，防止客户端超时
        String startMessage = JSONUtil.toJsonStr(Map.of(
            "type", "START",
            "message", "开始生成代码...",
            "timestamp", System.currentTimeMillis()
        ));
        
        // 1. 构建 sessionId
        String sessionId = buildSessionId(loginUser.getId(), appId);
        if(codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT_AGENTIC){
            log.info("进入 Agentic 分支, appId={}, message={}", appId, userMessage);
            // 关键：走多 Agent 协作支线
            Flux<String> stringFlux = vueAgenticOrchestrator.generateProject(userMessage, appId, loginUser);
            return Flux.concat(
                    Flux.just(startMessage),
                    stringFlux
            );
        }
        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getService(codeGenTypeEnum);
        Flux<String> actualStream = switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = service.generateHtmlCodeStream(sessionId, userMessage);
                yield streamHandlerExecutor.executeTextStream(codeStream, appId, loginUser, CodeGenTypeEnum.HTML);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = service.generateMultiFileCodeStream(sessionId, userMessage);
                yield streamHandlerExecutor.executeTextStream(codeStream, appId, loginUser, CodeGenTypeEnum.MULTI_FILE);
            }
            case VUE_PROJECT -> {
                VuePlanContext planContext = vuePlanningService.plan(userMessage);
                String enrichedUserMessage = "【用户原始需求】\n"
                        + planContext.getUserRequirement()
                        + "\n\n【项目规划(JSON)】\n"
                        + planContext.getPlanJson()
                        + "\n\n【脚手架步骤计划】\n"
                        + planContext.getScaffoldSteps()
                        + "\n";

                TokenStream skeletonStream = service.generateVueSkeletonStream(sessionId, enrichedUserMessage);
                Flux<String> skeletonFlux = streamHandlerExecutor.executeTokenStream(skeletonStream, appId, loginUser);

                String projectUserMessage = "【阶段说明】你已经根据以上规划和骨架生成结果，获得了一个最小可运行的 Vue3+Vite 项目骨架。现在请在现有骨架的基础上补充页面内容和业务逻辑，避免无意义地重复覆写已经存在的文件，除非为了修正明显错误或补充必要配置。\n\n"
                        + enrichedUserMessage;
                TokenStream projectStream = service.generateVueProjectCodeStream(sessionId, projectUserMessage);
                Flux<String> projectFlux = streamHandlerExecutor.executeTokenStream(projectStream, appId, loginUser);

                Flux<String> combinedFlux = Flux.concat(
                        Flux.just("【规划阶段】已生成项目规划和脚手架步骤...\n"),
                        Flux.just("【Vue 骨架阶段】开始生成项目骨架...\n"),
                        skeletonFlux,
                        Flux.just("【Vue 骨架阶段】已完成，开始完整项目生成（在现有骨架上补充页面和逻辑）...\n"),
                        projectFlux
                );
                yield combinedFlux;
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };

//        // 7. 添加回滚逻辑
//        Flux<String> streamWithRollback = actualStream
//            .doOnComplete(() -> {
//                // 生成成功
//                log.info("流式代码生成成功, userId: {}, appId: {}, cost: {}",
//                    loginUser.getId(), appId, cost);
//            })
//            .doOnError(error -> {
//                // 生成失败，回滚积分
//                log.error("流式代码生成失败，回滚积分, userId: {}, cost: {}",
//                    loginUser.getId(), cost, error);
//                userWalletService.rollback(loginUser.getId(), cost);
//            })
//            .doOnCancel(() -> {
//                // 用户取消，回滚积分
//                log.warn("用户取消生成，回滚积分, userId: {}, cost: {}",
//                    loginUser.getId(), cost);
//                userWalletService.rollback(loginUser.getId(), cost);
//            });
        
        // 使用 Flux.concat 先返回初始消息，再返回完整实际数据流
        return Flux.concat(
            Flux.just(startMessage),
            actualStream
        );
    }


    

    /**
     * 降级方法 (Fallback)
     * 参数必须和原方法一致，最后多一个 Throwable
     */
    public Flux<String> generateFallback(String sessionId, String userMessage, Throwable t) {
        log.error("AI 服务调用失败，触发降级，sessionId: {}", sessionId, t);

        // 返回兜底的友好的流式消息
        String fallbackJson = JSONUtil.toJsonStr(Map.of(
                "type", "ERROR",
                "message", "AI 服务暂时不可用（触发熔断保护），请稍后再试。\n错误信息：" + t.getMessage()
        ));

        return Flux.just(fallbackJson);
    }

}
