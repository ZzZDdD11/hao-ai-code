#!/bin/bash

# 代码审计功能快速测试脚本
# 使用方法: chmod +x test_audit.sh && ./test_audit.sh

echo "========================================"
echo "  代码审计功能联调测试"
echo "========================================"
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试计数
PASS=0
FAIL=0

# 测试函数
test_case() {
    local name=$1
    local command=$2
    local expected=$3
    
    echo -n "测试: $name ... "
    
    result=$(eval $command 2>&1)
    
    if echo "$result" | grep -q "$expected"; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASS++))
        return 0
    else
        echo -e "${RED}✗ 失败${NC}"
        echo "  预期: $expected"
        echo "  实际: $result"
        ((FAIL++))
        return 1
    fi
}

echo "阶段 1: Python 服务测试"
echo "----------------------------------------"

# 测试 1: 健康检查
test_case "Python 健康检查" \
    "curl -s http://localhost:8001/api/audit/health" \
    "ok"

# 测试 2: 简单代码审计
test_case "简单代码审计" \
    "curl -s -X POST http://localhost:8001/api/audit/code -H 'Content-Type: application/json' -d '{\"code\": \"public class Test {}\", \"language\": \"java\"}'" \
    "audit_result"

# 测试 3: SQL 注入检测
echo ""
echo "测试: SQL 注入检测 ... "
sql_result=$(curl -s -X POST http://localhost:8001/api/audit/code \
  -H "Content-Type: application/json" \
  -d '{"code": "String sql = \"SELECT * FROM users WHERE id = \" + userId; jdbc.execute(sql);", "language": "java"}')

if echo "$sql_result" | grep -q "audit_result"; then
    risk_level=$(echo "$sql_result" | grep -o '"risk_level":"[^"]*"' | cut -d'"' -f4)
    echo -e "${GREEN}✓ 通过${NC} (风险等级: $risk_level)"
    ((PASS++))
else
    echo -e "${RED}✗ 失败${NC}"
    ((FAIL++))
fi

echo ""
echo "阶段 2: Redis 检查"
echo "----------------------------------------"

# 测试 4: Redis 连接
test_case "Redis 连接" \
    "redis-cli PING" \
    "PONG"

# 测试 5: 审计结果存在
echo -n "测试: Redis 中的审计结果 ... "
audit_keys=$(redis-cli KEYS "audit_result:*" 2>&1)
if [ -n "$audit_keys" ] && [ "$audit_keys" != "(empty array)" ]; then
    echo -e "${GREEN}✓ 通过${NC} (找到 $(echo "$audit_keys" | wc -l) 条记录)"
    ((PASS++))
else
    echo -e "${YELLOW}⚠ 警告${NC} (暂无审计结果，需要先生成代码)"
fi

echo ""
echo "========================================"
echo "  测试结果汇总"
echo "========================================"
echo -e "通过: ${GREEN}$PASS${NC}"
echo -e "失败: ${RED}$FAIL${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过！联调成功！${NC}"
    exit 0
else
    echo -e "${RED}✗ 有 $FAIL 个测试失败，请检查日志${NC}"
    exit 1
fi
