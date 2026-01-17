package com.hao.haoaicode.core;

import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.AiCodeGeneratorService;
import com.hao.haoaicode.ai.model.HtmlCodeResult;
import com.hao.haoaicode.ai.model.MultiFileCodeResult;
import com.hao.haoaicode.ai.AiCodeGeneratorServiceFactory;
import com.hao.haoaicode.ai.model.message.AiResponseMessage;
import com.hao.haoaicode.ai.model.message.ToolExecutedMessage;
import com.hao.haoaicode.ai.model.message.ToolRequestMessage;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.core.builder.VueProjectBuilder;
import com.hao.haoaicode.exception.BusinessException;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.CodeGenTypeEnum;
import com.hao.haoaicode.parser.CodeParserExecutor;
import com.hao.haoaicode.saver.CodeFileSaverExecutor;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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
    private VueProjectBuilder vueProjectBuilder;

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
     * 通用流式代码处理方法
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @param appId
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            String completeCode = codeBuilder.toString();
            Mono.fromCallable(() -> {
                        // 代码解析
                        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                        // 代码保存
                        return CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(savedDir -> log.info("保存成功: {}", savedDir.getAbsolutePath()))
                    .doOnError(e -> log.error("保存失败", e))
                    .subscribe();
        });
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
        // 1. 构建 sessionId
        String sessionId = buildSessionId(loginUser.getId(), appId);
        
        // 2. 获取共享服务（不再传 appId）
        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getService(codeGenTypeEnum);
        
        return switch (codeGenTypeEnum) {
            case HTML -> {
                // 3. 调用时传入 sessionId
                HtmlCodeResult result = service.generateHtmlCode(sessionId, userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = service.generateMultiFileCode(sessionId, userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, User loginUser) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        
        // 立即返回初始消息，防止客户端超时
        String startMessage = JSONUtil.toJsonStr(Map.of(
            "type", "START",
            "message", "开始生成代码...",
            "timestamp", System.currentTimeMillis()
        ));
        
        // 1. 构建 sessionId
        String sessionId = buildSessionId(loginUser.getId(), appId);
        
        // 2. 获取共享服务（不再传 appId 和 codeGenType）
        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getService(codeGenTypeEnum);
        
        Flux<String> actualStream = switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = service.generateHtmlCodeStream(sessionId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = service.generateMultiFileCodeStream(sessionId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = service.generateVueProjectCodeStream(sessionId, userMessage);
                yield processTokenStream(tokenStream, String.valueOf(appId));
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
        
        // 使用 Flux.concat 先返回初始消息，再返回实际数据流
        return Flux.concat(
            Flux.just(startMessage),
            actualStream
        );
    }


    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream, String appId) {
        return Flux.create(sink -> {
            // ✅ 注册取消回调 - 当前端断开连接时触发
            sink.onCancel(() -> {
                log.info("客户端取消订阅，appId: {}", appId);
                // 这里可以做一些清理工作，比如记录监控指标
            });
            
            tokenStream.onPartialResponse((String partialResponse) -> {
                        // ✅ 可选：检查是否已取消，避免无效推送
                        if (sink.isCancelled()) {
                            return;
                        }
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        if (sink.isCancelled()) return;
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        if (sink.isCancelled()) return;
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        // ✅ 如果已取消，跳过构建
                        if (sink.isCancelled()) {
                            log.info("已取消，跳过 Vue 项目构建");
                            return;
                        }
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                        vueProjectBuilder.buildProject(projectPath);
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }

}
