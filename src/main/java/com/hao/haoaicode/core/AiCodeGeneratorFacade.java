package com.hao.haoaicode.core;

import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.AiCodeGeneratorService;
import com.hao.haoaicode.ai.model.HtmlCodeResult;
import com.hao.haoaicode.ai.model.MultiFileCodeResult;
import com.hao.haoaicode.ai.AiCodeGeneratorServiceFactory;
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
    private VuePlanningService vuePlanningService;
    @Resource
    private com.hao.haoaicode.core.builder.VueStaticSkeletonBuilder vueStaticSkeletonBuilder;

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
    // @CircuitBreaker(name = "aiService", fallbackMethod = "generateFallback")
    // @Retry(name = "aiService")
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
                // 1. 优化：不再使用 LLM 生成规划和骨架，而是直接生成静态骨架
                // VuePlanContext planContext = vuePlanningService.plan(userMessage);

                // 2. 生成静态骨架
                // 注意：这里需要知道保存路径，但 Facade 层通常不直接处理文件路径细节（由 Service/Saver 处理）
                // 这里的逻辑稍微 tricky，因为 generateVueProjectCodeStream 是流式的，
                // 而静态骨架生成是同步的。为了保持一致性，我们可以在这里先生成骨架。
                // 但是！Service 层的 generateVueProjectCodeStream 期望的是 TokenStream。
                // 实际上，CodeFileSaverExecutor 会在最后把生成的文件写入磁盘。
                // 但是 VUE_PROJECT 比较特殊，它需要先有骨架，再在上面改。

                // 让我们看下原有的逻辑：
                // 1. generateVueSkeletonStream -> 生成骨架代码流 -> streamHandlerExecutor 保存骨架
                // 2. generateVueProjectCodeStream -> 生成业务代码流 -> streamHandlerExecutor 保存业务代码

                // 现在我们要把第1步替换为静态生成。
                // 我们可以直接调用 vueStaticSkeletonBuilder 生成到临时目录？
                // 不行，因为 appId 对应的目录是在 Saver 里决定的。

                // 方案调整：
                // 我们依然保持两阶段流，但是第一阶段我们伪造一个“骨架生成完成”的流，
                // 并且在流的执行过程中（或者执行前）真正去写文件。
                // 但是写文件需要 appId，这里有 appId。
                // 目标目录是 AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;

                String projectPath = com.hao.haoaicode.constant.AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                vueStaticSkeletonBuilder.generateSkeleton(projectPath);

                // 3. 构造提示词，告诉 LLM 骨架已就绪
                String projectUserMessage = "【阶段说明】项目骨架（Vue3+Vite+Router+AntDesignVue）已经生成完毕。请根据用户需求，在现有骨架基础上补充具体的页面代码和业务逻辑。\n" +
                        "【用户需求】\n" + userMessage + "\n\n" +
                        "【注意】\n" +
                        "- 不要重新生成 package.json, vite.config.js, main.js 等基础配置文件，除非需要添加特定依赖。\n" +
                        "- 重点生成 views/ 下的页面组件和 router/index.js 的路由配置。\n";

                TokenStream projectStream = service.generateVueProjectCodeStream(sessionId, projectUserMessage);
                Flux<String> projectFlux = streamHandlerExecutor.executeTokenStream(projectStream, appId, loginUser);

                Flux<String> combinedFlux = Flux.concat(
                        Flux.just(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", "【优化模式】已跳过耗时的规划阶段，直接使用标准高性能模板生成项目骨架...\n"))),
                        Flux.just(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", "【Vue 骨架阶段】标准骨架生成完毕。\n"))),
                        Flux.just(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", "【业务生成阶段】AI 正在根据您的需求编写业务代码...\n"))),
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
