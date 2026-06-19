#!/usr/bin/env bash
# =============================================================================
# RAG 整条链路 curl 调试脚本
# 直接调用 Spring Boot 后端,验证登录 → RAG 检索 → 知识库管理 整条链路。
# 与 Python 脚本的区别:不绕开 Java 服务,直接走生产代码路径。
# =============================================================================
set -euo pipefail

# ----------------------------- 配置 -----------------------------
# 后端地址。两种取法:
#   1) 直连 Spring Boot: http://localhost:8080
#   2) 走 nginx 反代:    http://localhost/api/admin/v1
BASE_URL="${BASE_URL:-http://localhost:8080}"
# BASE_URL="${BASE_URL:-http://localhost/api/admin/v1}"   # 走 nginx

# 测试账号(来自 sql/01_test_data.sql,密码统一 123456,后端 MD5 校验)
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-123456}"

# Qdrant HTTP REST 端口(Java 走 gRPC 6334,这里用 HTTP 6333 看状态)
QDRANT_HOST="${QDRANT_HOST:-localhost}"
QDRANT_HTTP_PORT="${QDRANT_HTTP_PORT:-6333}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-fitness_knowledge}"

# MySQL(用于把 vector_status 改成 pending 触发 KnowledgeIndexJob)
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DB="${MYSQL_DB:-fitness_admin}"
MYSQL_USER="${MYSQL_USER:-fitness}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-Health_2@26}"

# ----------------------------- 工具函数 -----------------------------
hr() { printf '\n%s\n' "------------------------------------------------------------"; }
ok() { printf "\033[32m[OK]\033[0m %s\n" "$*"; }
warn() { printf "\033[33m[WARN]\033[0m %s\n" "$*"; }
err() { printf "\033[31m[ERROR]\033[0m %s\n" "$*" >&2; }

jq_get() {  # 从 JSON 串中按 key 取值
  python3 -c "import sys, json; d=json.loads(sys.stdin.read());
keys=sys.argv[1].split('.')
for k in keys:
    if k.isdigit(): d=d[int(k)]
    else: d=d[k]
print(d if d is not None else '')" "$1"
}

# ----------------------------- 1. 服务存活 -----------------------------
hr; echo "[1] 后端健康检查 ${BASE_URL}"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${BASE_URL}/actuator/health" 2>/dev/null || echo "000")
if [[ "$HTTP_CODE" != "200" ]]; then
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${BASE_URL}/auth/login" -X POST -H "Content-Type: application/json" -d '{}' 2>/dev/null || echo "000")
fi
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "400" || "$HTTP_CODE" == "401" || "$HTTP_CODE" == "500" ]]; then
  ok "服务可达 (HTTP ${HTTP_CODE})"
else
  err "服务不可达 (HTTP ${HTTP_CODE})"
  echo "请检查: 1) Spring Boot 是否启动 2) BASE_URL 是否正确 3) 端口是否开放"
  exit 1
fi

# ----------------------------- 2. 登录拿 token -----------------------------
hr; echo "[2] 登录后台 (${USERNAME})"
LOGIN_BODY=$(jq -nc --arg u "$USERNAME" --arg p "$PASSWORD" '{username:$u, password:$p}')
LOGIN_RESP=$(curl -s --max-time 10 -X POST "${BASE_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d "$LOGIN_BODY")
echo "  响应: ${LOGIN_RESP}"

TOKEN=$(echo "$LOGIN_RESP" | jq_get 'data.token' 2>/dev/null || true)
if [[ -z "$TOKEN" ]]; then
  err "登录失败,无法获取 token"
  echo "可能原因:"
  echo "  1) 账号密码错(测试账号: admin/123456, editor/123456, aiadmin/123456)"
  echo "  2) 账号未初始化: 在 MySQL 跑 sql/01_test_data.sql"
  echo "  3) 走 nginx 转发, 登录限流被命中( nginx limit_req burst=5 )"
  exit 1
fi
ok "登录成功,token: ${TOKEN:0:24}..."

# sa-token 头:Bearer <token>
AUTH_HEADER="Authorization: Bearer ${TOKEN}"

# ----------------------------- 3. 验证 token 有效 -----------------------------
hr; echo "[3] 验证 token 有效(获取当前用户)"
PROFILE=$(curl -s --max-time 5 -X GET "${BASE_URL}/auth/profile" -H "$AUTH_HEADER")
echo "  响应: ${PROFILE}"
if [[ "$(echo "$PROFILE" | jq_get 'code' 2>/dev/null)" == "200" ]]; then
  ok "token 有效,当前用户: $(echo "$PROFILE" | jq_get 'data.username')"
else
  err "token 校验失败"
  exit 1
fi

# ----------------------------- 4. 知识库列表(看 vector_status 分布) -----------------------------
hr; echo "[4] 知识库列表(看哪些是 pending / indexed / failed)"
KB_LIST=$(curl -s --max-time 10 -X GET "${BASE_URL}/ai-knowledge/list?pageNum=1&pageSize=20" -H "$AUTH_HEADER")
echo "$KB_LIST" | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())
rows = d.get('data', {}).get('records') or d.get('data', {}).get('list') or []
if not rows:
    print('  (空,需要先 INSERT 种子数据: sql/01_test_data.sql)')
    sys.exit(0)
print(f'  共 {len(rows)} 条:')
for r in rows:
    print(f\"    id={r.get('id'):<3} status={r.get('vectorStatus','?'):<8} title={r.get('title')}\")
" || warn "解析响应失败: $KB_LIST"

# ----------------------------- 5. 把指定 id 改成 pending 触发索引(可选) -----------------------------
FORCE_ID="${FORCE_ID:-}"  # e.g. FORCE_ID=1 重新索引第 1 条
if [[ -n "$FORCE_ID" ]] && command -v mysql >/dev/null 2>&1; then
  hr; echo "[5] 把 knowledge_base id=${FORCE_ID} 状态改成 pending 触发 KnowledgeIndexJob"
  mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" \
    -e "UPDATE knowledge_base SET vector_status='pending' WHERE id=${FORCE_ID};" 2>&1 | grep -v "Using a password" || true
  ok "已置为 pending,等待 KnowledgeIndexJob (60s 周期) 处理"
  echo "  也可以手动触发: 等 60s 后看日志,或重启服务"
elif [[ -n "$FORCE_ID" ]]; then
  warn "未检测到 mysql 命令,跳过;请手动在 MySQL 中执行:"
  echo "    UPDATE knowledge_base SET vector_status='pending' WHERE id=${FORCE_ID};"
fi

# ----------------------------- 6. Qdrant collection 状态 -----------------------------
hr; echo "[6] Qdrant collection 状态"
QDRANT_INFO=$(curl -s --max-time 5 "http://${QDRANT_HOST}:${QDRANT_HTTP_PORT}/collections/${QDRANT_COLLECTION}")
echo "$QDRANT_INFO" | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())
if d.get('status') != 'ok':
    print('  collection 不存在或查询失败:', d)
    sys.exit(0)
r = d['result']
print(f\"  status: {r['status']}\")
print(f\"  points_count: {r['points_count']}\")
print(f\"  indexed_vectors_count: {r['indexed_vectors_count']}\")
print(f\"  vectors.size: {r['config']['params']['vectors']['size']}\")
print(f\"  vectors.distance: {r['config']['params']['vectors']['distance']}\")
" || warn "解析失败: $QDRANT_INFO"

# ----------------------------- 7. RAG 检索测试(核心) -----------------------------
hr; echo "[7] RAG 检索测试 — 直接调后端 /ai-knowledge/rag-test"
echo "  这一步会走完整链路: Java EmbeddingService → Qdrant(Java gRPC) → RagRetriever"

run_rag() {
  local query="$1"
  local topk="${2:-5}"
  local body
  body=$(jq -nc --arg q "$query" --argjson k "$topk" '{query:$q, topK:$k}')
  echo
  echo "  Q: ${query}  (topK=${topk})"
  local resp
  resp=$(curl -s --max-time 30 -X POST "${BASE_URL}/ai-knowledge/rag-test" \
    -H "Content-Type: application/json" \
    -H "$AUTH_HEADER" \
    -d "$body")
  echo "  R: ${resp}"
  echo "$resp" | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())
if d.get('code') != 200:
    print('  调用失败:', d.get('message') or d)
    sys.exit(0)
data = d.get('data') or {}
hits = data.get('results') or []
print(f\"  命中: {data.get('hitCount')}\")
for i, h in enumerate(hits, 1):
    print(f\"    #{i} score={h.get('score', 0):.4f}  title={h.get('title')}  category={h.get('category')}  sourceId={h.get('id')}\")
" 2>/dev/null || warn "解析失败"
}

run_rag "新手减脂应该怎么安排训练?" 3
run_rag "增肌每天要吃多少蛋白质?" 3
run_rag "训练后肌肉酸痛怎么办?" 3
run_rag "膝盖有伤能做什么动作?" 3
run_rag "推拉腿怎么分配?" 3

# ----------------------------- 8. 完整 RAG 命中率(用于看历史检索质量) -----------------------------
hr; echo "[8] RAG 命中率统计(管理后台)"
curl -s --max-time 5 -X GET "${BASE_URL}/ai-knowledge/analytics/rag-hit-rate" -H "$AUTH_HEADER" | python3 -m json.tool 2>/dev/null || true

hr; echo "[完成]"
echo "如果第 [7] 步 hits=0,可能原因:"
echo "  1) Qdrant collection 是空(points_count=0) → 等 KnowledgeIndexJob 或重启服务"
echo "  2) 阈值过高(0.6),所有命中 score<0.6 → 调小 ai.rag-min-score"
echo "  3) Embedding 调用失败 → 看后端日志: 搜索 'Embedding'"
echo "  4) DashScope key 失效 → 检查 deploy/fitness-admin.env AI_EMBEDDING_API_KEY"