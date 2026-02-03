package com.hao.haoaicode.controller;

import com.hao.haoaicode.common.BaseResponse;
import com.hao.haoaicode.common.ResultUtils;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.exception.ThrowUtils;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.review.RagEnhancementService;
import com.hao.haoaicode.review.model.RagResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 测试接口
 * 用于测试 RAG 功能是否正常工作
 */
@Slf4j
@RestController
@RequestMapping("/rag")
public class RagTestController {
    
    @Resource
    private RagEnhancementService ragEnhancementService;

    @Resource
    private CosManager cosManager;
    
    /**
     * 测试 RAG 检索功能
     * 
     * 示例：GET /api/rag/test?query=XSS攻击防护
     * 
     * @param query 查询内容
     * @return RAG 响应
     */
    @GetMapping("/test")
    public BaseResponse<RagResponse> testRag(@RequestParam String query) {
        log.info("收到 RAG 测试请求，query: {}", query);
        
        // 1. 参数校验
        ThrowUtils.throwIf(query == null || query.trim().isEmpty(), 
                ErrorCode.PARAMS_ERROR, "查询内容不能为空");
        
        // 2. 调用 RAG 服务
        // 暂时关闭向量和图谱检索（因为还没导入数据）
        RagResponse response = ragEnhancementService.callRagApi(query, false, false);
        
        // 3. 返回结果
        if (response != null) {
            log.info("RAG 检索成功，耗时: {}s", response.getLatency());
            return ResultUtils.success(response);
        } else {
            log.warn("RAG 检索失败或返回 null");
            return (BaseResponse<RagResponse>) ResultUtils.error(ErrorCode.SYSTEM_ERROR, "RAG 服务调用失败");
        }
    }
    
    /**
     * 测试检索安全文档
     * 
     * 示例：GET /api/rag/security?codeType=html
     * 
     * @param codeType 代码类型
     * @return 检索到的文档
     */
    @GetMapping("/security")
    public BaseResponse<String> testSecurityDocs(
            @RequestParam(defaultValue = "html") String codeType) {
        log.info("收到安全文档检索请求，codeType: {}", codeType);

        String docs = ragEnhancementService.retrieveSecurityDocs(codeType);

        log.info("检索到文档长度: {}", docs.length());
        return ResultUtils.success(docs);
    }

    /**
     * COS 连通性测试（写入一个小对象）
     * 示例：GET /api/rag/cos/ping
     */
    @GetMapping("/cos/ping")
    public BaseResponse<String> pingCos() {
        String key = "connectivity-test/ping-" + System.currentTimeMillis() + ".txt";
        boolean ok = cosManager.uploadTextFile(key, "ping", "text/plain; charset=UTF-8");
        ThrowUtils.throwIf(!ok, ErrorCode.SYSTEM_ERROR, "COS ping failed");
        return ResultUtils.success(key);
    }
}
