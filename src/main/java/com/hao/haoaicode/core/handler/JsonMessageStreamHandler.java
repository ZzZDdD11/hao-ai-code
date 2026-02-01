package com.hao.haoaicode.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hao.haoaicode.ai.model.message.AiResponseMessage;
import com.hao.haoaicode.ai.model.message.ToolExecutedMessage;
import com.hao.haoaicode.ai.model.message.ToolRequestMessage;
import com.hao.haoaicode.buffer.ChatMessageRouter;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.core.builder.VueProjectBuilder;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应
 * 职责：
 * 1. 将 TokenStream 转换为前端可用的 JSON 字符串流
 * 2. 收集对话历史
 * 3. 触发 Vue 项目构建
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ChatMessageRouter chatMessageRouter;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 直接消费 TokenStream，避免了反复序列化/反序列化 JSON 的开销
     *
     * @param tokenStream 原始 TokenStream
     * @param appId       应用ID
     * @param loginUser   登录用户
     * @return 转换后的 Flux<String>（JSON格式）
     */
    public Flux<String> handle(TokenStream tokenStream, long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();

        return Flux.create(sink -> {
            // 注册取消回调
            sink.onCancel(() -> {
                log.info("客户端取消订阅，appId: {}", appId);
            });

            tokenStream.onPartialResponse((String partialResponse) -> {
                        if (sink.isCancelled()) return;

                        // 1. 发送给前端（包装为JSON）
                        String jsonResponse = JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", partialResponse));
                        sink.next(jsonResponse);

                        // 2. 收集历史记录
                        chatHistoryStringBuilder.append(partialResponse);
                    })
                    .beforeToolExecution((beforeToolExecutionHandler) -> {
                        if (sink.isCancelled()) return;
                            String toolId = beforeToolExecutionHandler.request().id();
                            // 如果工具是第一次出现，添加说明
                            if (toolId != null && !seenToolIds.contains(toolId)) {
                                seenToolIds.add(toolId);
                                String marker = "\n\n[选择工具] 写入文件\n\n";
                                chatHistoryStringBuilder.append(marker);
                                sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", marker)));  // 发给前端作为一段说明
                            }
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        if (sink.isCancelled()) return;
                        
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        String result = formatToolExecutionResult(toolExecutedMessage);  // 里边解析 relativeFilePath & content
                        String output = "\n\n" + result + "\n\n";

                        // 1. 完整记录到历史（保证上下文完整）
                        chatHistoryStringBuilder.append(output);

                        // 2. 尝试解析并发送结构化数据给前端
                        try {
                            JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                            String relativeFilePath = jsonObject.getStr("relativeFilePath");
                            String content = jsonObject.getStr("content");
                            
                            // 发送文件数据事件
                            Map<String, Object> fileData = Map.of(
                                "filePath", relativeFilePath,
                                "content", content
                            );
                            sink.next(JSONUtil.toJsonStr(Map.of("type", "file_generated", "data", JSONUtil.toJsonStr(fileData))));
                            
                            // 发送简短的提示消息到聊天界面
                            String simpleMsg = "\n> 已生成文件: `" + relativeFilePath + "`\n";
                            sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", simpleMsg)));
                            
                        } catch (Exception e) {
                            // 解析失败，回退到发送完整文本
                            log.error("解析工具参数失败", e);
                            sink.next(JSONUtil.toJsonStr(Map.of("type", "ai_response", "data", output)));
                        }
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        if (sink.isCancelled()) {
                            log.info("已取消，跳过 Vue 项目构建");
                            return;
                        }

                        // 3. 触发 Vue 项目构建 (原 Facade 逻辑)
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                        vueProjectBuilder.buildProjectAsync(projectPath);

                        // 4. 保存对话历史 (原 Handler 逻辑)
                        String aiResponse = chatHistoryStringBuilder.toString();
                        chatMessageRouter.route(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());

                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                        
                        // 记录错误历史
                        String errorMessage = "AI回复失败: " + error.getMessage();
                        chatMessageRouter.route(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    })
                    .start();
        });
    }

    /**
     * 格式化工具执行结果，用于历史记录展示
     */
    private String formatToolExecutionResult(ToolExecutedMessage toolExecutedMessage) {
        try {
            JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
            String relativeFilePath = jsonObject.getStr("relativeFilePath");
            String suffix = FileUtil.getSuffix(relativeFilePath);
            String content = jsonObject.getStr("content");
            return String.format("""
                    [工具调用] 写入文件 %s
                    ```%s
                    %s
                    ```
                    """, relativeFilePath, suffix, content);
        } catch (Exception e) {
            log.error("格式化工具执行结果失败", e);
            return "[工具执行结果格式化失败]";
        }
    }
}
