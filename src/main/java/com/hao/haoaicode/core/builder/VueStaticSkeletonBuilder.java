package com.hao.haoaicode.core.builder;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 静态 Vue 项目骨架生成器
 * 替代 LLM 生成骨架，大幅提升速度
 */
@Component
@Slf4j
public class VueStaticSkeletonBuilder {

    /**
     * 生成静态骨架
     * @param projectPath 项目根目录
     */
    public void generateSkeleton(String projectPath) {
        log.info("开始生成静态 Vue 骨架: {}", projectPath);
        FileUtil.mkdir(projectPath);

        // 1. package.json
        String packageJson = """
                {
                  "name": "vue-project",
                  "version": "0.0.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build",
                    "preview": "vite preview"
                  },
                  "dependencies": {
                    "vue": "^3.3.4",
                    "vue-router": "^4.2.4",
                    "ant-design-vue": "^4.0.0",
                    "axios": "^1.6.0"
                  },
                  "devDependencies": {
                    "@vitejs/plugin-vue": "^4.2.3",
                    "vite": "^4.4.5"
                  }
                }
                """;
        FileUtil.writeString(packageJson, new File(projectPath, "package.json"), StandardCharsets.UTF_8);

        // 2. vite.config.js
        String viteConfig = """
                import { defineConfig } from 'vite'
                import vue from '@vitejs/plugin-vue'
                import { fileURLToPath, URL } from 'node:url'

                export default defineConfig({
                  plugins: [vue()],
                  resolve: {
                    alias: {
                      '@': fileURLToPath(new URL('./src', import.meta.url))
                    }
                  },
                  base: './',
                  server: {
                    host: '0.0.0.0'
                  }
                })
                """;
        FileUtil.writeString(viteConfig, new File(projectPath, "vite.config.js"), StandardCharsets.UTF_8);

        // 3. index.html
        String indexHtml = """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8">
                    <link rel="icon" href="/favicon.ico">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Vue App</title>
                  </head>
                  <body>
                    <div id="app"></div>
                    <script type="module" src="/src/main.js"></script>
                  </body>
                </html>
                """;
        FileUtil.writeString(indexHtml, new File(projectPath, "index.html"), StandardCharsets.UTF_8);

        // 4. src 目录
        String srcPath = projectPath + "/src";
        FileUtil.mkdir(srcPath);

        // 5. src/main.js
        String mainJs = """
                import { createApp } from 'vue'
                import App from './App.vue'
                import router from './router'
                import Antd from 'ant-design-vue';
                import 'ant-design-vue/dist/reset.css';

                const app = createApp(App)
                app.use(router)
                app.use(Antd)
                app.mount('#app')
                """;
        FileUtil.writeString(mainJs, new File(srcPath, "main.js"), StandardCharsets.UTF_8);

        // 6. src/App.vue
        String appVue = """
                <script setup>
                import { RouterView } from 'vue-router'
                </script>

                <template>
                  <RouterView />
                </template>

                <style>
                #app {
                  width: 100%;
                  height: 100vh;
                }
                </style>
                """;
        FileUtil.writeString(appVue, new File(srcPath, "App.vue"), StandardCharsets.UTF_8);

        // 7. src/router/index.js
        String routerPath = srcPath + "/router";
        FileUtil.mkdir(routerPath);
        String routerJs = """
                import { createRouter, createWebHashHistory } from 'vue-router'

                const router = createRouter({
                  history: createWebHashHistory(),
                  routes: [
                    {
                      path: '/',
                      redirect: '/home'
                    },
                    {
                        path: '/home',
                        name: 'home',
                        component: () => import('../views/HomeView.vue')
                    }
                  ]
                })

                export default router
                """;
        FileUtil.writeString(routerJs, new File(routerPath, "index.js"), StandardCharsets.UTF_8);

        // 8. src/views/HomeView.vue (默认首页)
        String viewsPath = srcPath + "/views";
        FileUtil.mkdir(viewsPath);
        String homeView = """
                <template>
                  <div class="home-container">
                    <h1>欢迎使用 AI 代码生成器</h1>
                    <p>项目骨架已生成，AI 正在为您编写业务代码...</p>
                  </div>
                </template>

                <style scoped>
                .home-container {
                    padding: 20px;
                    text-align: center;
                }
                </style>
                """;
        FileUtil.writeString(homeView, new File(viewsPath, "HomeView.vue"), StandardCharsets.UTF_8);

        log.info("静态 Vue 骨架生成完成");
    }
}
