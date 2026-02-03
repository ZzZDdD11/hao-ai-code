package com.hao.haoaicode.core.builder;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RuntimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class VueProjectBuilder {

    private static final ThreadLocal<String> LAST_ERROR = new ThreadLocal<>();

    /**
     * 异步构建项目（不阻塞主流程）
     *
     * @param projectPath 项目路径
     */
    public void buildProjectAsync(String projectPath) {
        // 在单独的线程中执行构建，避免阻塞主流程
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                buildProject(projectPath);
            } catch (Exception e) {
                log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
            }
        });
    }
    /**
     * 执行命令
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否执行成功
     */
    public String getLastError() {
        return LAST_ERROR.get();
    }

    private void clearLastError() {
        LAST_ERROR.remove();
    }

    private static void drain(InputStream inputStream, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
    }

    private boolean executeCommand(File workingDir, String command, int timeoutSeconds) {
        clearLastError();
        String[] args = command.split("\\s+");
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
            Process process = RuntimeUtil.exec(null, workingDir, args);

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread t1 = Thread.ofVirtual().name("cmd-stdout").start(() -> drain(process.getInputStream(), stdout));
            Thread t2 = Thread.ofVirtual().name("cmd-stderr").start(() -> drain(process.getErrorStream(), stderr));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                t1.join(500);
                t2.join(500);
                String msg = "命令执行超时（" + timeoutSeconds + "秒），已终止进程\n" +
                        "workDir=" + workingDir.getAbsolutePath() + "\n" +
                        "command=" + command + "\n" +
                        "stdout:\n" + stdout + "\n" +
                        "stderr:\n" + stderr;
                LAST_ERROR.set(msg);
                log.error(msg);
                return false;
            }

            int exitCode = process.exitValue();
            t1.join(500);
            t2.join(500);

            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return true;
            }

            String msg = "命令执行失败，退出码: " + exitCode + "\n" +
                    "workDir=" + workingDir.getAbsolutePath() + "\n" +
                    "command=" + command + "\n" +
                    "stdout:\n" + stdout + "\n" +
                    "stderr:\n" + stderr;
            LAST_ERROR.set(msg);
            log.error(msg);
            return false;
        } catch (Exception e) {
            String msg = "执行命令异常\n" +
                    "workDir=" + (workingDir == null ? null : workingDir.getAbsolutePath()) + "\n" +
                    "command=" + command + "\n" +
                    "error=" + e.getMessage();
            LAST_ERROR.set(msg);
            log.error(msg, e);
            return false;
        }
    }

    /**
     * 执行 npm install 命令
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        String command = String.format("%s install --registry=https://registry.npmmirror.com", buildCommand("npm"));
        return executeCommand(projectDir, command, 300); // 5分钟超时
    }

    /**
     * 执行 npm run build 命令
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        String command = String.format("%s run build", buildCommand("npm"));
        return executeCommand(projectDir, command, 180); // 3分钟超时
    }

    private boolean ensureDependencyInstalled(File projectDir, String packageName) {
        if (projectDir == null || packageName == null || packageName.isBlank()) {
            return false;
        }
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists() || !packageJson.isFile()) {
            return false;
        }
        String json;
        try {
            json = FileUtil.readUtf8String(packageJson);
        } catch (Exception e) {
            log.warn("读取 package.json 失败: {}, error: {}", packageJson.getAbsolutePath(), e.getMessage());
            return false;
        }
        if (json != null && json.contains("\"" + packageName + "\"")) {
            return true;
        }
        String cmd = String.format("%s install %s --save --registry=https://registry.npmmirror.com", buildCommand("npm"), packageName);
        log.warn("检测到依赖缺失，尝试自动安装: {}, cmd: {}", packageName, cmd);
        return executeCommand(projectDir, cmd, 180);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    private int ensureRouterReferencedViewsExist(File projectDir) {
        File routerFile = new File(projectDir, "src/router/index.js");
        if (!routerFile.exists()) {
            routerFile = new File(projectDir, "src/router/index.ts");
        }
        if (!routerFile.exists() || !routerFile.isFile()) {
            return 0;
        }

        String routerText;
        try {
            routerText = FileUtil.readUtf8String(routerFile);
        } catch (Exception e) {
            log.warn("读取路由文件失败: {}, error: {}", routerFile.getAbsolutePath(), e.getMessage());
            return 0;
        }

        Pattern pattern = Pattern.compile("['\"]((?:@/|\\.{1,2}/)[^'\"]*views/[^'\"]+?\\.vue)['\"]");
        Matcher matcher = pattern.matcher(routerText);

        Set<String> paths = new HashSet<>();
        while (matcher.find()) {
            String p = matcher.group(1);
            if (p != null && !p.isBlank()) {
                paths.add(p.trim());
            }
        }
        if (paths.isEmpty()) {
            return 0;
        }

        String projectCanonical;
        try {
            projectCanonical = projectDir.getCanonicalPath();
        } catch (Exception e) {
            projectCanonical = projectDir.getAbsolutePath();
        }

        int created = 0;
        for (String refPath : paths) {
            File resolved;
            if (refPath.startsWith("@/")) {
                resolved = new File(projectDir, "src/" + refPath.substring(2));
            } else {
                resolved = new File(routerFile.getParentFile(), refPath);
            }

            File canonicalFile;
            try {
                canonicalFile = resolved.getCanonicalFile();
            } catch (Exception e) {
                canonicalFile = resolved.getAbsoluteFile();
            }

            String canonicalPath = canonicalFile.getPath();
            if (!canonicalPath.startsWith(projectCanonical)) {
                continue;
            }

            if (canonicalFile.exists()) {
                continue;
            }

            File parent = canonicalFile.getParentFile();
            if (parent != null) {
                FileUtil.mkdir(parent);
            }

            String fileName = canonicalFile.getName();
            String title = fileName.endsWith(".vue") ? fileName.substring(0, fileName.length() - 4) : fileName;
            String placeholder = """
                    <script setup>
                    </script>

                    <template>
                      <div class=\"page\">
                        <h1>%s</h1>
                        <p>该页面文件在生成输出中缺失，已自动创建占位页以保证构建通过。</p>
                      </div>
                    </template>

                    <style scoped>
                    .page {
                      padding: 24px;
                    }
                    </style>
                    """.formatted(title);
            try {
                FileUtil.writeString(placeholder, canonicalFile, StandardCharsets.UTF_8);
                created++;
            } catch (Exception e) {
                log.warn("创建占位页面失败: {}, error: {}", canonicalFile.getAbsolutePath(), e.getMessage());
            }
        }
        return created;
    }

    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        clearLastError();
        long start = System.currentTimeMillis();
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            LAST_ERROR.set("项目目录不存在: " + projectPath);
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            LAST_ERROR.set("package.json 文件不存在: " + packageJson.getAbsolutePath());
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }

        log.info("开始构建 Vue 项目: {}", projectPath);

        long installStart = System.currentTimeMillis();
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败, 耗时: {}ms", System.currentTimeMillis() - installStart);
            return false;
        }
        log.info("npm install 成功, 耗时: {}ms", System.currentTimeMillis() - installStart);

        int createdPlaceholders = ensureRouterReferencedViewsExist(projectDir);
        if (createdPlaceholders > 0) {
            log.warn("检测到路由引用的页面文件缺失，已自动创建占位页数量: {}", createdPlaceholders);
        }

        long buildStart = System.currentTimeMillis();
        if (!executeNpmBuild(projectDir)) {
            String detail = getLastError();
            if (detail != null && detail.contains("failed to resolve import \"pinia\"") && ensureDependencyInstalled(projectDir, "pinia")) {
                log.warn("已自动安装 pinia，重试 npm run build");
                if (executeNpmBuild(projectDir)) {
                    log.info("npm run build 成功（自动修复依赖后），耗时: {}ms", System.currentTimeMillis() - buildStart);
                } else {
                    log.error("npm run build 二次尝试失败, 耗时: {}ms", System.currentTimeMillis() - buildStart);
                    return false;
                }
            } else {
                log.error("npm run build 执行失败, 耗时: {}ms", System.currentTimeMillis() - buildStart);
                return false;
            }
        } else {
            log.info("npm run build 成功, 耗时: {}ms", System.currentTimeMillis() - buildStart);
        }

        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            LAST_ERROR.set("构建完成但 dist 目录未生成: " + distDir.getAbsolutePath());
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录: {}, 总耗时: {}ms", distDir.getAbsolutePath(), System.currentTimeMillis() - start);
        clearLastError();
        return true;
    }



}
