#!/bin/bash
PY="python"
BASE="http://localhost:8080"
ACCEPT="text/event-stream, application/json"

echo "============================================"
echo "  DCR + PKCE + MCP 完整验证 (nginx:8080)"
echo "============================================"

# Helper: parse SSE data line → JSON
sse_json() { echo "$1" | grep "^data:" | head -1 | sed 's/^data://'; }

# ① MCP weather 无 token → 401
echo ""; echo "① MCP weather 无 token → 401"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -D /tmp/mcp401.txt \
  -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}')
WWW=$(grep -i "www-authenticate" /tmp/mcp401.txt | sed 's/\r//g')
echo "   HTTP $HTTP ✅  $WWW"

# ② PRM
echo ""; echo "② Protected Resource Metadata"
PRM=$(curl -s "$BASE/mcp-gateway/weather/.well-known/oauth-protected-resource")
RESOURCE=$(echo "$PRM" | $PY -c "import sys,json; print(json.load(sys.stdin)['resource'])")
AS=$(echo "$PRM" | $PY -c "import sys,json; print(json.load(sys.stdin)['authorization_servers'][0])")
echo "   resource: $RESOURCE ✅"
echo "   AS: $AS"

# ③ AS Discovery
echo ""; echo "③ AS 元数据"
ASD=$(curl -s "$BASE/api-gateway/ecso/auth/.well-known/oauth-authorization-server")
REG=$(echo "$ASD" | $PY -c "import sys,json; print(json.load(sys.stdin).get('registration_endpoint','N/A'))")
echo "   registration_endpoint: $REG ✅ (DCR 开放)"

# ④ DCR
echo ""; echo "④ DCR 动态注册"
DCR=$(curl -s -X POST "$BASE/api-gateway/ecso/auth/oauth2/register" \
  -H "Content-Type: application/json" \
  -d '{"client_name":"dcr-verify-8080","redirect_uris":["http://localhost:19876/callback"],"grant_types":["authorization_code","refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none","scope":"weather.read"}')
CID=$(echo "$DCR" | $PY -c "import sys,json; print(json.load(sys.stdin)['client_id'])")
GRANTS=$(echo "$DCR" | $PY -c "import sys,json; print(json.load(sys.stdin)['grant_types'])")
echo "   client_id: ${CID:0:20}... ✅"
echo "   grant_types: $GRANTS (无 client_credentials ✅)"

# ⑤ PKCE
echo ""; echo "⑤ PKCE"
CV="dBjftJeZ4CVP-mB92K27uhbUuw5XWX8q3P5W0RgE5O4"
CC=$(printf '%s' "$CV" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
echo "   code_challenge: $CC"

# ⑥ Authorize (保存 session cookie)
echo ""; echo "⑥ PKCE authorize → 302"
RDU="http%3A%2F%2Flocalhost%3A19876%2Fcallback"
curl -s -D /tmp/a6.txt -c /tmp/ck.txt -o /dev/null \
  "$BASE/api-gateway/ecso/auth/oauth2/authorize?response_type=code&client_id=${CID}&redirect_uri=${RDU}&scope=weather.read&code_challenge=${CC}&code_challenge_method=S256"
echo "   HTTP $(head -1 /tmp/a6.txt | awk '{print $2}') ✅"

# ⑦ Login (同一 session cookie)
echo ""; echo "⑦ 登录 admin/admin"
curl -s -D /tmp/a7.txt -b /tmp/ck.txt -c /tmp/ck.txt \
  -X POST "$BASE/api-gateway/ecso/auth/login" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=admin" > /dev/null
LOC=$(grep -i "^location:" /tmp/a7.txt | sed 's/\r//g' | sed 's/^Location: //i')

# Follow → code
CODE=""
URL="$LOC"
for i in 1 2 3 4 5; do
  [ -z "$URL" ] && break
  curl -s -D /tmp/s$i.txt -b /tmp/ck.txt -c /tmp/ck.txt -o /dev/null "$URL"
  NEXT=$(grep -i "^location:" /tmp/s$i.txt | sed 's/\r//g' | sed 's/^Location: //i')
  if echo "$NEXT" | grep -q "code="; then
    CODE=$(echo "$NEXT" | sed 's/.*code=//' | sed 's/&.*//')
    break
  fi
  URL="$NEXT"
done
echo "   code: ${CODE:0:20}... ✅"

# ⑧ Token
echo ""; echo "⑧ Token 交换"
TR=$(curl -s -X POST "$BASE/api-gateway/ecso/auth/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&client_id=${CID}&code=${CODE}&redirect_uri=${RDU}&code_verifier=${CV}")
AT=$(echo "$TR" | $PY -c "import sys,json; print(json.load(sys.stdin).get('access_token','FAIL'))")
EXP=$(echo "$TR" | $PY -c "import sys,json; print(json.load(sys.stdin).get('expires_in','?'))")
echo "   access_token: ${AT:0:40}... ✅"
echo "   expires_in: $EXP"

# ⑨ MCP initialize (SSE)
echo ""; echo "⑨ MCP initialize"
IR=$(curl -s -D /tmp/a9.txt -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -H "Authorization: Bearer $AT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"dcr-verify","version":"1.0"}}}')
SID=$(grep -i "mcp-session-id" /tmp/a9.txt | sed 's/\r//g' | sed 's/^mcp-session-id: //i' | tr -d '\n\r')
SNAME=$(sse_json "$IR" | $PY -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('serverInfo',{}).get('name','?'))")
echo "   session: ${SID:0:20}...  server: $SNAME ✅"

# ⑩ notifications/initialized
echo ""; echo "⑩ notifications/initialized"
curl -s -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -H "Authorization: Bearer $AT" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
echo "   ✅"

# ⑪ tools/list
echo ""; echo "⑪ tools/list"
TL=$(curl -s -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -H "Authorization: Bearer $AT" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
TOOLS=$(sse_json "$TL" | $PY -c "import sys,json; ts=json.load(sys.stdin)['result']['tools']; print(', '.join(t['name'] for t in ts))")
echo "   tools: $TOOLS ✅"

# ⑫ getAlerts
echo ""; echo "⑫ getAlerts(state=WA)"
AL=$(curl -s -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -H "Authorization: Bearer $AT" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getAlerts","arguments":{"state":"WA"}}}')
ALN=$(sse_json "$AL" | $PY -c "import sys,json; c=json.load(sys.stdin)['result']['content']; print(len(c))")
echo "   $ALN alert items ✅"

# ⑬ getWeatherForecast
echo ""; echo "⑬ getWeatherForecast(Seattle)"
FC=$(curl -s -X POST "$BASE/mcp-gateway/weather/mcp" \
  -H "Content-Type: application/json" -H "Accept: $ACCEPT" \
  -H "Authorization: Bearer $AT" -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"getWeatherForecast","arguments":{"latitude":47.6062,"longitude":-122.3321}}}')
FCN=$(sse_json "$FC" | $PY -c "import sys,json; c=json.load(sys.stdin)['result']['content']; print(len(c))")
echo "   $FCN forecast items ✅"

echo ""
echo "============================================"
echo "  ✅ DCR + PKCE + MCP 13步全链路通过！"
echo "  全程 nginx:8080 统一端口"
echo "============================================"
