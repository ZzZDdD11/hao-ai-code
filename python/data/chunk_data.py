from langchain_community.document_loaders import TextLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import Chroma
import re
from openai import OpenAI
from dotenv import load_dotenv
import os

load_dotenv()

LLM_CLIENT = None
LLM_MODEL = "deepseek-chat"

def get_llm_client():
    """获取或初始化LLM客户端"""
    global LLM_CLIENT
    if LLM_CLIENT is None:
        api_key = os.getenv("DEEPSEEK_API_KEY")
        if api_key:
            LLM_CLIENT = OpenAI(
                api_key=api_key,
                base_url="https://api.deepseek.com"
            )
        else:
            print("警告: 未找到 DEEPSEEK_API_KEY，LLM服务将不可用")
    return LLM_CLIENT

# 加载文件
file_path = "rag_data/react.zh.md"
loader = TextLoader(file_path, encoding="utf-8")
docs = loader.load()

# 配置切分器
text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000, # 容纳完整的【bad + good】规则单元
    chunk_overlap=100, # 防止示例片段被切散
    separators=["\n\n", "\n// ", "\n", "//", ";", "{", "}", " "],  # 优先拆分规则单元，保护注释+代码块
    length_function=len,
)

# 执行切分
split_docs = text_splitter.split_documents(docs)
print(f"分割后文档数量：{len(split_docs)}")

# # 保存切分后的文档
# import json

# # 确保输出目录存在
# import os
# os.makedirs("rag_data", exist_ok=True)

# # 将Document对象转换为字典并保存为JSONL
# output_path = "rag_data/javascript_splits.jsonl"
# with open(output_path, "w", encoding="utf-8") as f:
#     for doc in split_docs:
#         doc_dict = {
#             "page_content": doc.page_content,
#             "metadata": doc.metadata
#         }
#         json.dump(doc_dict, f, ensure_ascii=False)
#         f.write("\n")

# print(f"已保存切分结果到：{output_path}")

# 打印切分结果（验证是否按规则单元拆分）
# print(f"切分后总片段数：{len(splits_docs)}")  # 预期3个片段（对应3条规则）
# for i, doc in enumerate(splits_docs):
#     print(f"\n=== 片段{i+1}（完整规则单元）===")
#     print(doc.page_content)

# ===================== 第二步：构建向量库 =====================
# 1. 初始化嵌入模型（适配代码+中文，本地CPU运行）
embeddings = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",  # 代码/中文双适配，轻量（~100MB）
    model_kwargs={"device": "cpu"},       # 纯CPU，无需GPU
    encode_kwargs={"normalize_embeddings": True}  # 归一化，提升检索精度
)

# 2. 构建并持久化向量库（本地存储，路径：./chroma_db/alibaba_eslint）
vector_db = Chroma.from_documents(
    documents=split_docs,
    embedding=embeddings,
    persist_directory="./chroma_db/alibaba_eslint"  # 向量库保存路径
)
vector_db.persist()
print("向量库构建并持久化完成！")


# ===================== 第三步：检索优化（核心） =====================
def analyze_with_llm(code: str, relevant_rules: list) -> dict:
    """
    使用LLM分析代码质量问题并生成修复建议
    :param code: 待分析的代码
    :param relevant_rules: 检索到的相关规范规则
    :return: LLM分析结果
    """
    client = get_llm_client()
    if not client:
        return {
            "analysis": "LLM服务不可用",
            "issues": [],
            "suggestions": [],
            "fixed_code": None
        }
    
    # 构建上下文
    rules_context = "\n\n".join([
        f"规则 {i+1}:\n{rule.get('rule_content', '')}" 
        for i, rule in enumerate(relevant_rules[:5])
    ])
    
    prompt = f"""你是一个专业的代码质量审查专家。请分析以下代码，并结合提供的开发规范给出详细的审查结果。

## 代码:
```javascript
{code}
```

## 相关开发规范:
{rules_context}

请按以下格式返回JSON格式的分析结果：

{{
    "summary": "代码质量总体评价（2-3句话）",
    "issues": [
        {{
            "rule_id": "触发的规范ID（如QUALITY_ISSUE_001）",
            "issue_type": "问题类型（如：代码规范、安全风险、性能问题）",
            "severity": "严重程度（HIGH/MEDIUM/LOW）",
            "description": "问题的具体描述",
            "line_number": "问题所在行号（如无法确定则为null）",
            "code_snippet": "有问题的代码片段"
        }}
    ],
    "suggestions": [
        {{
            "issue_id": "对应的issue编号",
            "suggestion": "具体的修复建议",
            "reason": "为什么这样修改"
        }}
    ],
    "fixed_code": "修复后的完整代码（如果可以给出）。如果没有问题则为null。",
    "additional_tips": ["其他优化建议（可选）"]
}}

请确保返回严格的JSON格式，不要添加额外的markdown代码块标记。"""
    
    try:
        response = client.chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {
                    "role": "system", 
                    "content": "你是一个专业的代码质量审查专家，专注于JavaScript/React代码规范和最佳实践分析。"
                },
                {
                    "role": "user", 
                    "content": prompt
                }
            ],
            temperature=0.3,
            max_tokens=3000
        )
        
        content = response.choices[0].message.content
        
        # 提取JSON部分（去除可能的markdown代码块标记）
        import re
        json_match = re.search(r'\{[\s\S]*\}', content)
        if json_match:
            json_str = json_match.group(0)
            import json
            result = json.loads(json_str)
            print("LLM分析完成")
            return result
        else:
            print(f"无法从LLM响应中提取JSON: {content}")
            return {
                "summary": "代码分析完成，但未能解析详细结果",
                "issues": [],
                "suggestions": [],
                "fixed_code": None,
                "raw_response": content
            }
        
    except json.JSONDecodeError as e:
        print(f"LLM返回格式错误: {e}")
        print(f"原始响应: {response.choices[0].message.content if 'response' in locals() else 'No response'}")
        return {
            "summary": "LLM分析失败，返回格式异常",
            "issues": [],
            "suggestions": [],
            "fixed_code": None,
            "error": str(e)
        }
    except Exception as e:
        print(f"LLM调用失败: {e}")
        return {
            "summary": "LLM服务暂时不可用",
            "issues": [],
            "suggestions": [],
            "fixed_code": None,
            "error": str(e)
        }

def enhance_code_query(code: str) -> str:
    """
    代码检索增强：提取代码中的关键信息（变量、函数、报错关键词），提升匹配度
    :param code: 待分析的前端代码
    :return: 增强后的检索词
    """
    # 1. 去除注释和空白符
    code_clean = re.sub(r"//.*|/\*.*?\*/", "", code).strip()
    # 2. 提取关键语法：函数名、变量名、报错相关关键词
    func_names = re.findall(r"function\s+(\w+)|const\s+(\w+)\s*=", code_clean)
    error_keywords = re.findall(r"ReferenceError|undefined|return", code_clean)
    # 3. 拼接增强检索词
    key_words = set([item for sublist in func_names for item in sublist if item] + error_keywords)
    enhance_query = f"{code_clean} {' '.join(key_words)}"
    return enhance_query

def retrieve_relevant_rules(code: str, top_k: int = 2, score_threshold: float = 0.3) -> list:
    """
    精准检索相关规则
    :param code: 待分析代码
    :param top_k: 最多返回top_k条规则
    :param score_threshold: 相似度阈值（低于0.3视为无关）
    :return: 匹配的规则列表
    """
    # 1. 增强检索词
    enhance_query = enhance_code_query(code)
    print(f"增强后的检索词：{enhance_query}")
    
    # 2. 带分数的相似度检索
    retrieved_docs = vector_db.similarity_search_with_score(enhance_query, k=top_k)
    print(f"检索到 {len(retrieved_docs)} 个候选文档")
    
    # 3. 过滤低相似度结果
    relevant_docs = []
    for doc, score in retrieved_docs:
        print(f"候选文档相似度分数：{score:.4f}")
        if score <= score_threshold:  # Chroma的score越小，相似度越高
            relevant_docs.append({
                "score": score,
                "rule_content": doc.page_content
            })
    return relevant_docs

# ===================== FastAPI 服务 =====================
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional, Any
import uvicorn

# 创建 FastAPI 应用
app = FastAPI(
    title="代码质量检查服务",
    description="基于 RAG 的代码质量检查 API",
    version="1.0.0"
)

# 允许跨域请求
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 请求和响应模型
class CodeCheckRequest(BaseModel):
    code: str
    language: str = "javascript"

class FixInfo(BaseModel):
    recommendation: Optional[str] = None
    code_example: Optional[str] = None
    references: Optional[List[str]] = None

class LLMAnalysisResult(BaseModel):
    summary: str
    issues: Optional[List[dict]] = None
    suggestions: Optional[List[dict]] = None
    fixed_code: Optional[str] = None
    additional_tips: Optional[List[str]] = None
    error: Optional[str] = None

class VulnerabilityInfo(BaseModel):
    id: str
    type: str
    severity: str
    confidence: str
    location: Optional[str] = None
    description: Optional[str] = None
    impact: Optional[str] = None
    fix: Optional[FixInfo] = None

class AuditResult(BaseModel):
    summary: str
    risk_level: str
    security_score: int
    vulnerabilities: Optional[List[VulnerabilityInfo]] = None
    llm_analysis: Optional[LLMAnalysisResult] = None
    error: Optional[str] = None

class CodeCheckResponse(BaseModel):
    audit_result: AuditResult

@app.get("/health")
async def health_check():
    """健康检查接口"""
    return {
        "status": "healthy",
        "service": "code-quality-check",
        "vector_db": "loaded",
        "rules_count": len(split_docs)
    }

@app.post("/api/check/quality", response_model=CodeCheckResponse)
async def check_code_quality(request: CodeCheckRequest):
    """
    代码质量检查 API
    接收代码，使用向量检索+RAG+LLM智能分析，返回质量检查结果
    """
    try:
        code = request.code
        language = request.language
        
        if not code:
            return CodeCheckResponse(
                audit_result=AuditResult(
                    summary="待检查代码为空",
                    risk_level="UNKNOWN",
                    security_score=0,
                    error="待检查代码为空"
                )
            )
        
        print(f"收到代码质量检查请求，语言: {language}, 代码长度: {len(code)}")
        
        # 使用向量检索匹配相关规范
        relevant_rules = retrieve_relevant_rules(code, top_k=5, score_threshold=0.6)
        
        # 构建质量检查结果
        if not relevant_rules:
            return CodeCheckResponse(
                audit_result=AuditResult(
                    summary="代码质量检查完成，未发现明显的规范问题",
                    risk_level="SAFE",
                    security_score=100,
                    vulnerabilities=[]
                )
            )
        
        # 调用LLM进行智能分析
        print("开始调用LLM进行代码分析...")
        llm_result = analyze_with_llm(code, relevant_rules)
        
        # 将检索到的规范和LLM分析转换为质量检查结果
        vulnerabilities = []
        
        # 从LLM分析中提取问题列表
        llm_issues = llm_result.get("issues", [])
        if llm_issues:
            for i, issue in enumerate(llm_issues):
                vulnerability = VulnerabilityInfo(
                    id=issue.get("rule_id", f"QUALITY_ISSUE_{i+1:03d}"),
                    type=issue.get("issue_type", "代码规范"),
                    severity=issue.get("severity", "MEDIUM"),
                    confidence="HIGH",
                    location=f"Line {issue.get('line_number', 'unknown')}" if issue.get("line_number") else "unknown",
                    description=issue.get("description", ""),
                    impact="代码可读性、可维护性或安全性问题",
                    fix=FixInfo(
                        recommendation=issue.get("suggestion", "请参考规范文档进行修复"),
                        code_example=issue.get("code_snippet", ""),
                        references=[
                            "https://eslint.org/docs/rules/",
                            "https://vuejs.org/style-guide/"
                        ]
                    )
                )
                vulnerabilities.append(vulnerability)
        else:
            # 如果LLM没有返回详细问题，回退到基于规则的方法
            for i, rule in enumerate(relevant_rules):
                score = rule['score']
                content = rule['rule_content']
                
                # 确定严重性
                if score < 0.3:
                    severity = "HIGH"
                elif score < 0.5:
                    severity = "MEDIUM"
                else:
                    severity = "LOW"
                
                # 构建问题对象
                vulnerability = VulnerabilityInfo(
                    id=f"QUALITY_ISSUE_{i+1:03d}",
                    type="代码规范",
                    severity=severity,
                    confidence="HIGH",
                    location="unknown",
                    description=content[:200] if content else None,
                    impact="代码可读性和可维护性降低，可能引入潜在bug",
                    fix=FixInfo(
                        recommendation="请参考规范文档进行修复",
                        code_example=content[:500] if content else None,
                        references=[
                            "https://eslint.org/docs/rules/",
                            "https://vuejs.org/style-guide/"
                        ]
                    )
                )
                vulnerabilities.append(vulnerability)
        
        # 计算质量评分
        avg_score = sum([rule['score'] for rule in relevant_rules]) / len(relevant_rules)
        quality_score = int(max(0, min(100, (1 - avg_score) * 100)))
        
        # 如果LLM返回了固定的代码，适当调整评分
        if llm_result.get("fixed_code"):
            quality_score = max(quality_score, 70)
        
        # 确定风险等级
        if any(v.severity == "HIGH" for v in vulnerabilities):
            risk_level = "HIGH"
        elif any(v.severity == "MEDIUM" for v in vulnerabilities):
            risk_level = "MEDIUM"
        else:
            risk_level = "LOW"
        
        # 构建LLM分析结果
        llm_analysis = LLMAnalysisResult(
            summary=llm_result.get("summary", ""),
            issues=llm_result.get("issues", []),
            suggestions=llm_result.get("suggestions", []),
            fixed_code=llm_result.get("fixed_code"),
            additional_tips=llm_result.get("additional_tips", []),
            error=llm_result.get("error")
        )
        
        response = CodeCheckResponse(
            audit_result=AuditResult(
                summary=llm_result.get("summary", f"代码质量检查完成，发现 {len(vulnerabilities)} 个规范问题"),
                risk_level=risk_level,
                security_score=quality_score,
                vulnerabilities=vulnerabilities,
                llm_analysis=llm_analysis
            )
        )
        
        print(f"质量检查完成，问题数: {len(vulnerabilities)}, 质量评分: {quality_score}")
        
        # 打印完整的响应内容（调试用）
        import json
        print("\n" + "=" * 80)
        print("完整响应内容：")
        print(json.dumps(response.model_dump(), ensure_ascii=False, indent=2))
        print("=" * 80 + "\n")
        
        return response
        
    except Exception as e:
        print(f"代码质量检查失败: {str(e)}")
        raise HTTPException(
            status_code=500,
            detail={
                "audit_result": {
                    "summary": "质量检查失败",
                    "risk_level": "UNKNOWN",
                    "security_score": 0,
                    "error": str(e)
                }
            }
        )

@app.post("/api/audit/code", response_model=CodeCheckResponse)
async def audit_code_legacy(request: CodeCheckRequest):
    """
    兼容旧版的代码审计接口
    """
    return await check_code_quality(request)

if __name__ == "__main__":
    # 启动 FastAPI 服务
    print("=" * 60)
    print("启动代码质量检查 FastAPI 服务...")
    print("=" * 60)
    print("API 文档: http://localhost:8001/docs")
    print("健康检查: http://localhost:8001/health")
    print("代码检查: http://localhost:8001/api/check/quality")
    print("兼容接口: http://localhost:8001/api/audit/code")
    print("=" * 60)
    
    # 启动服务
    uvicorn.run(
        app, 
        host="0.0.0.0", 
        port=8001,
        reload=False  # 生产环境设置为 False
    )