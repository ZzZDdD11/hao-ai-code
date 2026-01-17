package com.hao.haoaicode.review;

import cn.hutool.core.util.StrUtil;
import com.hao.haoaicode.review.model.CodeAuditRequest;
import com.hao.haoaicode.review.model.CodeAuditResponse;
import com.hao.haoaicode.review.model.RagRequest;
import com.hao.haoaicode.review.model.RagResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * RAG 增强服务
 * 负责调用 Python RAG 服务进行知识检索
 */
@Slf4j
@Service
public class RagEnhancementService {
    
    @Value("${rag.service.url:http://localhost:8000}")
    private String ragServiceUrl;
    
    @Value("${rag.enabled:true}")
    private Boolean ragEnabled;
    
    @Value("${rag.mock:false}")
    private Boolean ragMock;
    
    private final RestTemplate restTemplate;
    
    public RagEnhancementService(@Qualifier("ragRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    /**
     * 调用 RAG API 检索相关文档
     * 
     * @param query 查询内容
     * @param enableVector 是否启用向量检索
     * @param enableGraph 是否启用知识图谱
     * @return RAG 响应，失败时返回 null
     */
    public RagResponse callRagApi(String query, boolean enableVector, boolean enableGraph) {
        // 1. 检查是否启用
        if (!ragEnabled) {
            log.info("RAG 功能未启用");
            return null;
        }
        
        // 2. 参数校验
        if (StrUtil.isBlank(query)) {
            log.warn("查询内容为空");
            return null;
        }

        // 3. Mock 模式：暂不支持（已移除）
        if (ragMock) {
            log.info("RAG Mock 模式，返回空结果");
            return null;
        }
        
        try {
            // 3. 构建请求
            RagRequest request = RagRequest.builder()
                    .query(query)
                    .enableVector(enableVector)
                    .enableGraph(enableGraph)
                    .enableWeb(false)  // 暂不启用网络搜索
                    .topK(3)
                    .build();
            
            // 4. 调用 API
            String url = ragServiceUrl + "/v1/chat";
            log.info("调用 RAG API: {}, query: {}", url, query);
            
            RagResponse response = restTemplate.postForObject(url, request, RagResponse.class);
            
            if (response != null) {
                log.info("RAG API 调用成功，耗时: {}s, 来源数: {}", 
                        response.getLatency(), 
                        response.getSources() != null ? response.getSources().size() : 0);
            }
            
            return response;
            
        } catch (Exception e) {
            // 5. 异常处理：降级，不影响主流程
            log.error("RAG API 调用失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 检索安全规范文档
     * 
     * @param codeType 代码类型（如 html, vue_project）
     * @return 检索到的文档内容
     */
    public String retrieveSecurityDocs(String codeType) {
        String query = String.format("%s 代码安全规范和最佳实践", codeType);
        RagResponse response = callRagApi(query, true, true);
        
        if (response == null || response.getSources() == null) {
            return "未找到相关安全规范";
        }
        
        // 拼接所有来源的内容
        StringBuilder docs = new StringBuilder();
        response.getSources().forEach(source -> {
            docs.append("[").append(source.getSourceType()).append("] ");
            docs.append(source.getContent());
            docs.append("\n\n");
        });
        
        return docs.toString();
    }

    /**
     * 代码生成后的 RAG 增强（演示版）
     * 
     * @param appId 应用ID
     * @param codeType 代码类型
     */
    public void enhanceGeneratedCode(Long appId, String codeType) {
        log.info("开始对生成的代码进行 RAG 增强，appId: {}, codeType: {}", appId, codeType);
        
        try {
            // 1. 构建查询
            String query = String.format("请分析 %s 代码的安全性和质量", codeType);
            
            // 2. 调用 RAG API（暂时关闭向量和图谱，因为还没数据）
            RagResponse response = callRagApi(query, false, false);
            
            // 3. 处理结果
            if (response != null) {
                log.info("RAG 增强成功，答案: {}", response.getAnswer());
                log.info("来源数量: {}", response.getSources() != null ? response.getSources().size() : 0);
                
                // TODO: 后续这里会：
                // 1. 读取生成的代码文件
                // 2. 将 RAG 检索结果注入到 Prompt
                // 3. 调用 AI 分析代码
                // 4. 生成审查报告
                // 5. 保存到 Redis
            } else {
                log.warn("RAG 增强失败，appId: {}", appId);
            }
            
        } catch (Exception e) {
            // 异常不影响主流程
            log.error("RAG 增强异常，appId: {}, error: {}", appId, e.getMessage());
        }
    }
    
    /**
     * 调用 Python RAG 服务进行代码审计
     * 
     * @param code 待审计的代码
     * @param language 代码语言
     * @return 审计结果
     */
    public CodeAuditResponse auditCode(String code, String language) {
        // 1. 检查是否启用
        if (!ragEnabled) {
            log.info("RAG 功能未启用");
            return createErrorAuditResponse("RAG 功能未启用");
        }
        
        // 2. 参数校验
        if (StrUtil.isBlank(code)) {
            log.warn("待审计代码为空");
            return createErrorAuditResponse("待审计代码为空");
        }
        
        // 3. Mock 模式：返回模拟审计结果
        if (ragMock) {
            log.info("RAG Mock 模式，返回模拟审计结果");
            return createMockAuditResponse();
        }
        
        try {
            // 4. 构建请求
            CodeAuditRequest request = CodeAuditRequest.builder()
                .code(code)
                .language(language)
                .enableGraph(true)
                .build();
            
            // 5. 调用 Python API
            String url = ragServiceUrl + "/api/audit/code";
            log.info("调用代码审计 API: {}, 代码长度: {}", url, code.length());
            
            CodeAuditResponse response = restTemplate.postForObject(
                url, request, CodeAuditResponse.class
            );
            
            if (response != null && response.getAuditResult() != null) {
                log.info("代码审计成功，风险等级: {}", 
                    response.getAuditResult().getRiskLevel());
            }
            
            return response;
            
        } catch (Exception e) {
            // 6. 异常处理：降级，不影响主流程
            log.error("代码审计失败: {}", e.getMessage());
            return createErrorAuditResponse(e.getMessage());
        }
    }
    
    /**
     * 创建错误审计响应
     * 
     * @param error 错误信息
     * @return 错误响应对象
     */
    private CodeAuditResponse createErrorAuditResponse(String error) {
        CodeAuditResponse response = new CodeAuditResponse();
        CodeAuditResponse.AuditResult result = new CodeAuditResponse.AuditResult();
        result.setSummary("审计失败");
        result.setRiskLevel("UNKNOWN");
        result.setSecurityScore(0);
        result.setError(error);
        response.setAuditResult(result);
        return response;
    }
    
    /**
     * 创建 Mock 审计响应（用于演示）
     * 
     * @return Mock 的审计结果
     */
    private CodeAuditResponse createMockAuditResponse() {
        CodeAuditResponse response = new CodeAuditResponse();
        
        // 审计结果
        CodeAuditResponse.AuditResult auditResult = new CodeAuditResponse.AuditResult();
        auditResult.setSummary("检测到 1 个高危漏洞：SQL 注入");
        auditResult.setRiskLevel("HIGH");
        auditResult.setSecurityScore(25);
        
        // 漏洞信息
        CodeAuditResponse.Vulnerability vuln = new CodeAuditResponse.Vulnerability();
        vuln.setId("SQL_INJECTION_001");
        vuln.setType("SQL Injection");
        vuln.setSeverity("HIGH");
        vuln.setConfidence("HIGH");
        vuln.setLocation("line 15");
        vuln.setDescription("用户输入直接拼接到 SQL 查询中，存在 SQL 注入风险");
        vuln.setTaintPath("HttpServletRequest.getParameter() -> String.format() -> jdbc.execute()");
        vuln.setImpact("攻击者可以执行任意 SQL 命令，导致数据泄露或篡改");
        vuln.setExploitScenario("攻击者输入: ' OR '1'='1 --");
        
        // 修复建议
        CodeAuditResponse.Fix fix = new CodeAuditResponse.Fix();
        fix.setRecommendation("使用 PreparedStatement 进行参数化查询");
        fix.setCodeExample("PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\nps.setString(1, userId);");
        
        vuln.setFix(fix);
        auditResult.setVulnerabilities(java.util.List.of(vuln));
        
        response.setAuditResult(auditResult);
        
        return response;
    }

}
