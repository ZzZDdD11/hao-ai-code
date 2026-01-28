import requests
from bs4 import BeautifulSoup
import json
import os

# 从ESLint-Vue规则页面提取规则
def extract_eslint_vue_rules():
    url = "https://eslint.vuejs.org/rules/"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 优先级映射
    priority_map = {
        "priority-a-essential-error-prevention": "严重",
        "priority-b-strongly-recommended-improving-code-readability": "高",
        "priority-c-recommended-minimizing-arbitrary-choices-and-cognitive-overhead": "中",
        "priority-d-fundamental-conventions": "低"
    }
    
    # 提取所有优先级的规则
    for section_id, priority in priority_map.items():
        section = soup.find("section", {"id": section_id})
        if section:
            for rule in section.find_all("h3"):
                rule_name = rule.text.strip()
                rule_desc = rule.find_next("p").text.strip()
                # 尝试提取更多详情
                details = ""
                next_sibling = rule.find_next("p").find_next_sibling()
                if next_sibling and next_sibling.name == "div" and "rule-details" in next_sibling.get("class", []):
                    details = next_sibling.text.strip()
                
                # 确定规则分类
                category = "基础语法规则"
                if "v-for" in rule_name or "key" in rule_name:
                    category = "基础语法规则"
                elif "v-if" in rule_name:
                    category = "性能优化规则"
                elif "security" in rule_name or "xss" in rule_name:
                    category = "安全防护规则"
                elif "component" in rule_name or "naming" in rule_name:
                    category = "可维护性规则"
                
                rules.append({
                    "name": rule_name,
                    "description": rule_desc,
                    "details": details,
                    "priority": priority,
                    "source": "ESLint-Vue",
                    "category": category,
                    "link": url
                })
    return rules

# 从Vue3官方风格指南提取规则
def extract_vue3_style_guide():
    url = "https://vuejs.org/style-guide/"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取Priority A类规则
    priority_a_section = soup.find("section", {"id": "priority-a-essential-error-prevention"})
    if priority_a_section:
        for rule in priority_a_section.find_all("h3"):
            rule_name = rule.text.strip()
            rule_desc = rule.find_next("p").text.strip()
            # 尝试提取更多详情
            details = ""
            next_sibling = rule.find_next("p").find_next_sibling()
            while next_sibling and next_sibling.name != "h3":
                details += next_sibling.text.strip() + "\n"
                next_sibling = next_sibling.find_next_sibling()
            
            # 确定规则分类
            category = "基础语法规则"
            if "v-for" in rule_name or "key" in rule_name:
                category = "基础语法规则"
            elif "v-if" in rule_name:
                category = "性能优化规则"
            elif "component" in rule_name or "naming" in rule_name:
                category = "可维护性规则"
            
            rules.append({
                "name": f"Vue3 {rule_name}",
                "description": rule_desc,
                "details": details,
                "priority": "严重",
                "source": "Vue3官方风格指南",
                "category": category,
                "link": url
            })
    
    return rules

# 从Vue3安全最佳实践提取规则
def extract_vue3_security_guide():
    url = "https://cn.vuejs.org/guide/best-practices/security"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取安全规则
    content_section = soup.find("main", {"id": "main-content"})
    if content_section:
        sections = content_section.find_all(["h2", "h3"])
        for section in sections:
            section_title = section.text.strip()
            # 尝试提取 section 内容
            content = ""
            next_elem = section.find_next_sibling()
            while next_elem and next_elem.name not in ["h2", "h3"]:
                content += next_elem.text.strip() + "\n"
                next_elem = next_elem.find_next_sibling()
            
            if content:
                rules.append({
                    "name": f"Vue3安全最佳实践：{section_title}",
                    "description": content[:500] + ("..." if len(content) > 500 else ""),
                    "details": content,
                    "priority": "严重",
                    "source": "Vue3官方安全最佳实践",
                    "category": "安全防护规则",
                    "link": url
                })
    
    return rules

# 从HTML5语义化标准提取规则
def extract_html5_semantics():
    url = "https://html.spec.whatwg.org/multipage/semantics.html"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取语义化标签相关内容
    content_section = soup.find("div", {"class": "head"}).find_next_sibling()
    if content_section:
        sections = content_section.find_all("h2")
        for section in sections[:10]:  # 限制提取数量，避免过多内容
            section_title = section.text.strip()
            # 尝试提取 section 内容
            content = ""
            next_elem = section.find_next_sibling()
            while next_elem and next_elem.name != "h2":
                content += next_elem.text.strip() + "\n"
                next_elem = next_elem.find_next_sibling()
            
            if content:
                rules.append({
                    "name": f"HTML5语义化标准：{section_title}",
                    "description": content[:500] + ("..." if len(content) > 500 else ""),
                    "details": content,
                    "priority": "中",
                    "source": "HTML5官方WHATWG语义化标准",
                    "category": "基础语法规则",
                    "link": url
                })
    
    return rules

# 从MDN HTML无障碍开发指南提取规则
def extract_mdn_accessibility():
    url = "https://developer.mozilla.org/zh-CN/docs/Learn_web_development/Core/Accessibility/HTML"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取无障碍开发规则
    content_section = soup.find("main", {"id": "main-content"})
    if content_section:
        sections = content_section.find_all(["h2", "h3"])
        for section in sections:
            section_title = section.text.strip()
            # 尝试提取 section 内容
            content = ""
            next_elem = section.find_next_sibling()
            while next_elem and next_elem.name not in ["h2", "h3"]:
                content += next_elem.text.strip() + "\n"
                next_elem = next_elem.find_next_sibling()
            
            if content:
                rules.append({
                    "name": f"HTML无障碍开发：{section_title}",
                    "description": content[:500] + ("..." if len(content) > 500 else ""),
                    "details": content,
                    "priority": "中",
                    "source": "MDN HTML无障碍开发指南",
                    "category": "可维护性规则",
                    "link": url
                })
    
    return rules

# 从阿里巴巴F2E前端规约提取规则
def extract_alibaba_f2e_spec():
    url = "https://github.com/alibaba/f2e-spec"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取README中的主要内容
    readme = soup.find("article", {"class": "markdown-body"})
    if readme:
        sections = readme.find_all(["h2", "h3"])
        for section in sections:
            section_title = section.text.strip()
            # 尝试提取 section 内容
            content = ""
            next_elem = section.find_next_sibling()
            while next_elem and next_elem.name not in ["h2", "h3"]:
                content += next_elem.text.strip() + "\n"
                next_elem = next_elem.find_next_sibling()
            
            if content:
                # 确定规则分类
                category = "可维护性规则"
                if "HTML" in section_title:
                    category = "基础语法规则"
                elif "安全" in section_title:
                    category = "安全防护规则"
                
                rules.append({
                    "name": f"阿里巴巴F2E规约：{section_title}",
                    "description": content[:500] + ("..." if len(content) > 500 else ""),
                    "details": content,
                    "priority": "中",
                    "source": "阿里巴巴F2E前端规约",
                    "category": category,
                    "link": url
                })
    
    return rules

# 从OWASP XSS防护手册提取规则
def extract_owasp_xss():
    url = "https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html"
    response = requests.get(url)
    soup = BeautifulSoup(response.text, "html.parser")
    rules = []
    
    # 提取XSS防护规则
    content_section = soup.find("div", {"class": "page-content"})
    if content_section:
        sections = content_section.find_all(["h2", "h3"])
        for section in sections:
            section_title = section.text.strip()
            # 尝试提取 section 内容
            content = ""
            next_elem = section.find_next_sibling()
            while next_elem and next_elem.name not in ["h2", "h3"]:
                content += next_elem.text.strip() + "\n"
                next_elem = next_elem.find_next_sibling()
            
            if content:
                rules.append({
                    "name": f"OWASP XSS防护：{section_title}",
                    "description": content[:500] + ("..." if len(content) > 500 else ""),
                    "details": content,
                    "priority": "严重",
                    "source": "OWASP XSS防护手册",
                    "category": "安全防护规则",
                    "link": url
                })
    
    return rules

# 保存为知识库文件（文本格式，符合用户示例格式）
def save_to_text(rules, filename):
    with open(filename, "w", encoding="utf-8") as f:
        for rule in rules:
            f.write(f"【{rule['name']}】（{rule['priority']}）\n")
            f.write(f"规则描述：{rule['description']}\n")
            if rule['details']:
                f.write(f"详细信息：{rule['details']}\n")
            f.write(f"适用类型：{rule['category']}\n")
            f.write(f"优先级：{rule['priority']}\n")
            f.write(f"来源：{rule['source']}\n")
            f.write(f"链接：{rule['link']}\n\n")

# 保存为JSON格式（适合RAG数据库）
def save_to_json(rules, filename):
    with open(filename, "w", encoding="utf-8") as f:
        json.dump(rules, f, ensure_ascii=False, indent=2)

# 主函数
def main():
    # 创建输出目录
    output_dir = "rag_data"
    os.makedirs(output_dir, exist_ok=True)
    
    # 爬取ESLint-Vue规则
    print("爬取ESLint-Vue规则中...")
    eslint_vue_rules = extract_eslint_vue_rules()
    print(f"成功提取 {len(eslint_vue_rules)} 条ESLint-Vue规则")
    
    # 爬取Vue3官方风格指南
    print("爬取Vue3官方风格指南中...")
    vue3_style_rules = extract_vue3_style_guide()
    print(f"成功提取 {len(vue3_style_rules)} 条Vue3风格指南规则")
    
    # 爬取Vue3官方安全最佳实践
    print("爬取Vue3官方安全最佳实践中...")
    vue3_security_rules = extract_vue3_security_guide()
    print(f"成功提取 {len(vue3_security_rules)} 条Vue3安全最佳实践规则")
    
    # 爬取HTML5语义化标准
    print("爬取HTML5语义化标准中...")
    html5_semantics_rules = extract_html5_semantics()
    print(f"成功提取 {len(html5_semantics_rules)} 条HTML5语义化标准规则")
    
    # 爬取MDN HTML无障碍开发指南
    print("爬取MDN HTML无障碍开发指南中...")
    mdn_accessibility_rules = extract_mdn_accessibility()
    print(f"成功提取 {len(mdn_accessibility_rules)} 条MDN无障碍开发规则")
    
    # 爬取阿里巴巴F2E前端规约
    print("爬取阿里巴巴F2E前端规约中...")
    alibaba_f2e_rules = extract_alibaba_f2e_spec()
    print(f"成功提取 {len(alibaba_f2e_rules)} 条阿里巴巴F2E规约规则")
    
    # 爬取OWASP XSS防护手册
    print("爬取OWASP XSS防护手册中...")
    owasp_xss_rules = extract_owasp_xss()
    print(f"成功提取 {len(owasp_xss_rules)} 条OWASP XSS防护规则")
    
    # 合并所有规则
    all_rules = (
        eslint_vue_rules + 
        vue3_style_rules + 
        vue3_security_rules + 
        html5_semantics_rules + 
        mdn_accessibility_rules + 
        alibaba_f2e_rules + 
        owasp_xss_rules
    )
    
    # 保存为文本格式
    text_filename = os.path.join(output_dir, "dev_guidelines.txt")
    save_to_text(all_rules, text_filename)
    print(f"已保存为文本格式：{text_filename}")
    
    # 保存为JSON格式
    json_filename = os.path.join(output_dir, "dev_guidelines.json")
    save_to_json(all_rules, json_filename)
    print(f"已保存为JSON格式：{json_filename}")

# 保存为知识库文件
if __name__ == "__main__":
    main()