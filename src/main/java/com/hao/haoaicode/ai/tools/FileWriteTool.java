package com.hao.haoaicode.ai.tools;

import com.hao.haoaicode.constant.AppConstant;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("[工具调用] 写入文件 %s", relativeFilePath);
    }

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId String sessionId
    ) {
        try {
            // 从 sessionId 中提取 appId（格式：userId:appId）
            Long appId = extractAppIdFromSessionId(sessionId);
            
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                // 相对路径处理，创建基于 appId 的项目目录
                String projectDirName = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                path = projectRoot.resolve(relativeFilePath);
            }
            // 创建父目录（如果不存在）
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, content.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功写入文件: {}", path.toAbsolutePath());
            // 注意要返回相对路径，不能让 AI 把文件绝对路径返回给用户
            return "文件写入成功: " + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 从 sessionId 中提取 appId
     * @param sessionId 格式：userId:appId
     * @return appId
     */
    private Long extractAppIdFromSessionId(String sessionId) {
        if (sessionId == null || !sessionId.contains(":")) {
            log.error("Invalid sessionId format: {}", sessionId);
            throw new IllegalArgumentException("Invalid sessionId format, expected userId:appId");
        }
        String[] parts = sessionId.split(":");
        if (parts.length != 2) {
            log.error("Invalid sessionId format: {}", sessionId);
            throw new IllegalArgumentException("Invalid sessionId format, expected userId:appId");
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Failed to parse appId from sessionId: {}", sessionId, e);
            throw new IllegalArgumentException("Invalid appId in sessionId: " + sessionId);
        }
    }
}

