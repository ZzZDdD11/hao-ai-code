package com.hao.haoaicode.review.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 代码审计响应对象
 */
@Data
public class CodeAuditResponse {
    /**
     * 审计结果
     */
    @JsonProperty("audit_result")
    private AuditResult auditResult;
    
    /**
     * 代码结构
     */
    @JsonProperty("code_structure")
    private CodeStructure codeStructure;
    
    /**
     * 依赖信息
     */
    private Dependencies dependencies;
    
    /**
     * 图谱路径
     */
    @JsonProperty("graph_paths")
    private List<GraphPath> graphPaths;
    
    /**
     * 审计结果
     */
    @Data
    public static class AuditResult {
        /**
         * 总体评估
         */
        private String summary;
        
        /**
         * 风险等级：HIGH, MEDIUM, LOW, SAFE
         */
        @JsonProperty("risk_level")
        private String riskLevel;
        
        /**
         * 漏洞列表
         */
        private List<Vulnerability> vulnerabilities;
        
        /**
         * 安全评分（0-100）
         */
        @JsonProperty("security_score")
        private Integer securityScore;
        
        /**
         * 合规性
         */
        private Compliance compliance;
        
        /**
         * 错误信息（如果审计失败）
         */
        private String error;
    }
    
    /**
     * 漏洞信息
     */
    @Data
    public static class Vulnerability {
        /**
         * 漏洞ID
         */
        private String id;
        
        /**
         * 漏洞类型
         */
        private String type;
        
        /**
         * 严重性：HIGH, MEDIUM, LOW
         */
        private String severity;
        
        /**
         * 确信度：HIGH, MEDIUM, LOW
         */
        private String confidence;
        
        /**
         * 代码位置
         */
        private String location;
        
        /**
         * 详细描述
         */
        private String description;
        
        /**
         * 污点路径
         */
        @JsonProperty("taint_path")
        private String taintPath;
        
        /**
         * 证据
         */
        private Evidence evidence;
        
        /**
         * 影响
         */
        private String impact;
        
        /**
         * 攻击场景
         */
        @JsonProperty("exploit_scenario")
        private String exploitScenario;
        
        /**
         * 修复建议
         */
        private Fix fix;
    }
    
    /**
     * 证据
     */
    @Data
    public static class Evidence {
        /**
         * 污点源节点
         */
        @JsonProperty("source_node")
        private String sourceNode;
        
        /**
         * 危险汇聚点节点
         */
        @JsonProperty("sink_node")
        private String sinkNode;
        
        /**
         * 缺失的清洗措施
         */
        @JsonProperty("missing_sanitizer")
        private String missingSanitizer;
        
        /**
         * 图谱路径
         */
        @JsonProperty("graph_path")
        private List<String> graphPath;
    }
    
    /**
     * 修复建议
     */
    @Data
    public static class Fix {
        /**
         * 修复建议
         */
        private String recommendation;
        
        /**
         * 安全代码示例
         */
        @JsonProperty("code_example")
        private String codeExample;
        
        /**
         * 参考标准
         */
        private List<String> references;
    }
    
    /**
     * 合规性
     */
    @Data
    public static class Compliance {
        /**
         * OWASP Top 10
         */
        @JsonProperty("owasp_top10")
        private List<String> owaspTop10;
        
        /**
         * CWE 编号
         */
        private List<String> cwe;
    }
    
    /**
     * 代码结构
     */
    @Data
    public static class CodeStructure {
        /**
         * 节点列表
         */
        private List<Node> nodes;
        
        /**
         * 边列表
         */
        private List<Edge> edges;
    }
    
    /**
     * 节点
     */
    @Data
    public static class Node {
        private String id;
        private String type;
        private String name;
        private String tag;
        private Map<String, Object> properties;
    }
    
    /**
     * 边
     */
    @Data
    public static class Edge {
        private String source;
        private String target;
        private String relation;
    }
    
    /**
     * 依赖信息
     */
    @Data
    public static class Dependencies {
        /**
         * 依赖列表
         */
        private List<Dependency> dependencies;
        
        /**
         * 高危依赖
         */
        @JsonProperty("high_risk_libs")
        private List<String> highRiskLibs;
    }
    
    /**
     * 依赖
     */
    @Data
    public static class Dependency {
        private String name;
        private String version;
        private String source;
        private String ecosystem;
        @JsonProperty("full_name")
        private String fullName;
    }
    
    /**
     * 图谱路径
     */
    @Data
    public static class GraphPath {
        private List<Map<String, Object>> nodes;
        private List<String> relations;
    }
}
