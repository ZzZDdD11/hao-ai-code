package com.hao.haoaicode.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.service.ProjectGenerationPostProcessor;
import com.hao.haoaicode.service.ProjectSpecService;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectSpecServiceImpl implements ProjectSpecService {

    @Resource
    private ProjectGenerationPostProcessor projectGenerationPostProcessor;
    @Resource
    private CosManager cosManager;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String buildProjectSpecPrompt(long appId) {
        SpecStructure structure = buildSpecStructure(appId);
        if (!structure.hasData()) {
            return "[项目工程规格-自动推断]\n当前进程未找到该应用的生成文件快照，请按照全局工程规范生成基础结构。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[项目工程规格-自动推断]\n");
        if (!structure.viewFiles.isEmpty()) {
            sb.append("当前项目中已存在以下视图组件文件：\n");
            for (String v : structure.viewFiles) {
                sb.append("- ").append(v).append("\n");
            }
        } else {
            sb.append("当前项目尚未发现任何视图组件文件（src/views/*.vue）。\n");
        }
        if (structure.routerFile != null) {
            sb.append("当前路由配置文件：\n");
            sb.append("- ").append(structure.routerFile).append("\n");
        }
        sb.append("请在上述结构基础上进行增量修改，避免随意删除已有页面和路由。");
        return sb.toString();
    }

    private SpecStructure buildSpecStructure(long appId) {
        Map<String, String> files = projectGenerationPostProcessor.getGeneratedFiles(appId);
        if (files != null && !files.isEmpty()) {
            List<String> viewFiles = new ArrayList<>();
            String routerFile = null;
            for (String path : files.keySet()) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                String normalized = path.replace("\\", "/");
                if (normalized.startsWith("src/views/") && normalized.endsWith(".vue")) {
                    viewFiles.add(normalized);
                } else if (routerFile == null
                        && (normalized.equals("src/router/index.ts") || normalized.equals("src/router/index.js"))) {
                    routerFile = normalized;
                }
            }
            return new SpecStructure(viewFiles, routerFile);
        }
        SpecStructure fromCos = loadFromCos(appId);
        if (fromCos != null && fromCos.hasData()) {
            return fromCos;
        }
        return new SpecStructure(new ArrayList<>(), null);
    }

    private SpecStructure loadFromCos(long appId) {
        String redisKey = String.format("code:source:latest:%d", appId);
        String baseKey = stringRedisTemplate.opsForValue().get(redisKey);
        if (baseKey == null || baseKey.isBlank()) {
            return new SpecStructure(new ArrayList<>(), null);
        }
        String tmpRoot = System.getProperty("java.io.tmpdir");
        File workDir = new File(tmpRoot, "spec-source-" + appId + "-" + System.currentTimeMillis());
        if (!workDir.exists() && !workDir.mkdirs()) {
            return new SpecStructure(new ArrayList<>(), null);
        }
        workDir.deleteOnExit();
        boolean ok = cosManager.downloadDirectory(baseKey, workDir);
        if (!ok) {
            return new SpecStructure(new ArrayList<>(), null);
        }
        List<String> viewFiles = new ArrayList<>();
        String routerFile = null;
        Path root = workDir.toPath();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relPath = root.relativize(path);
                String relative = relPath.toString().replace('\\', '/');
                if (relative.startsWith("src/views/") && relative.endsWith(".vue")) {
                    viewFiles.add(relative);
                } else if (routerFile == null
                        && (relative.equals("src/router/index.ts") || relative.equals("src/router/index.js"))) {
                    routerFile = relative;
                }
            }
        } catch (IOException e) {
            return new SpecStructure(new ArrayList<>(), null);
        }
        return new SpecStructure(viewFiles, routerFile);
    }

    private static class SpecStructure {
        private final List<String> viewFiles;
        private final String routerFile;

        private SpecStructure(List<String> viewFiles, String routerFile) {
            this.viewFiles = viewFiles;
            this.routerFile = routerFile;
        }

        private boolean hasData() {
            return (viewFiles != null && !viewFiles.isEmpty()) || routerFile != null;
        }
    }
}
