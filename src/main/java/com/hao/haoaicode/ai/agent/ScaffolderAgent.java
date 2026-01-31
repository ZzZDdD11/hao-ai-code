package com.hao.haoaicode.ai.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 负责：根据项目规划，生成 Vue3+Vite 项目骨架，并通过工具写入关键文件
 * 可以返回一段“操作日志”文本，用来流式展示给前端
 */
public interface ScaffolderAgent {
    @Agent(description = "根据 projectPlan 生成 Vue 项目骨架（package.json、Vite配置、路由、基础页面等）")
    @UserMessage("""
你是一个前端脚手架生成专家，负责根据项目规划给出“执行步骤列表”。

【任务】
- 分解脚手架生成过程，例如：创建目录、写入 package.json、写入 main.js、写入 App.vue、写入路由等。
- 不要实际执行任何命令，只返回“步骤计划”。

【输出格式（非常重要）】
- 严格按照以下格式输出，每个步骤一行，不要输出多余说明，不要写 Markdown，不要写 JSON：

  [STEP][步骤序号][动作类型][状态] file=相对路径 desc=中文描述

- 其中：
  - 步骤序号：从 1 开始递增的整数，例如 1、2、3...
  - 动作类型：CREATE_DIR / WRITE_FILE / UPDATE_DEP
  - 状态：一律填 PENDING（全部大写）
  - file：使用 POSIX 风格相对路径，例如：
      package.json
      vite.config.js
      src/main.js
      src/App.vue
      src/router/index.js
  - desc：用简短中文描述该步骤要做什么

【示例】（仅供参考，你生成时不要输出这一段）

  [STEP][1][CREATE_DIR][PENDING] file=src desc=创建 src 目录
  [STEP][2][WRITE_FILE][PENDING] file=package.json desc=创建项目配置文件（包含 Vue3 + Vite 依赖）

【项目规划】
{{projectPlanJson}}
""")
    String scaffoldProject(@V("projectPlanJson") String projectPlanJson);
}
