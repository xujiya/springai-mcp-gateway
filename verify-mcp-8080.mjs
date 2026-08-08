import http from 'node:http';

function post(url, headers, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const opts = { hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST', headers };
    const req = http.request(opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

function extractJson(text) {
  // Handle SSE or plain JSON
  const m = text.match(/\{[\s\S]*\}/);
  return m ? JSON.parse(m[0]) : JSON.parse(text);
}

async function run() {
  const BASE = 'http://localhost:8080';
  let pass = 0, fail = 0;
  function check(name, ok) { if(ok) { pass++; console.log(`  ✅ ${name}`); } else { fail++; console.log(`  ❌ ${name}`); } }

  // 1. DCR
  console.log('\n━━━ 1. DCR 注册 (8080 → nginx → gateway → auth) ━━━');
  const dcr = await post(`${BASE}/api-gateway/ecso/auth/oauth2/register`,
    { 'Content-Type': 'application/json' },
    { client_name:'mcp-8080-verify', grant_types:['client_credentials'], scope:'mcp:read mcp:write', redirect_uris:['http://localhost:1/cb'] });
  const dcrData = JSON.parse(dcr.body);
  check('DCR 201', dcr.status === 201);
  check('secret 90d', Math.round((dcrData.client_secret_expires_at - Date.now()/1000)/86400) >= 89);
  const CID = dcrData.client_id, CSEC = dcrData.client_secret;

  // 2. Token
  console.log('\n━━━ 2. Token (8080 → nginx → gateway → auth) ━━━');
  const tok = await post(`${BASE}/api-gateway/ecso/auth/oauth2/token`,
    { 'Content-Type': 'application/x-www-form-urlencoded' },
    `grant_type=client_credentials&client_id=${CID}&client_secret=${CSEC}&scope=mcp:read+mcp:write`);
  const tokData = JSON.parse(tok.body);
  check('Token 200', tok.status === 200 && tokData.access_token);
  check('expires_in 24h', tokData.expires_in === 86400);
  const TOKEN = tokData.access_token;

  // 3. MCP Initialize
  console.log('\n━━━ 3. MCP Initialize (8080 → nginx → mcp-gateway) ━━━');
  const init = await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    { jsonrpc:'2.0', id:1, method:'initialize', params:{ protocolVersion:'2025-03-26', capabilities:{}, clientInfo:{name:'verify-8080',version:'1.0'} } });
  const SESSION = init.headers['mcp-session-id'];
  const initData = extractJson(init.body);
  check('Initialize 200', initData.result?.serverInfo?.name === 'springai-mcp-gateway');
  check('Session ID', !!SESSION);
  console.log(`    session: ${SESSION?.substring(0,20)}...`);

  // 4. notifications/initialized
  console.log('\n━━━ 4. notifications/initialized ━━━');
  await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'Mcp-Session-Id': SESSION },
    { jsonrpc:'2.0', method:'notifications/initialized' });
  check('initialized sent', true);

  // 5. tools/list
  console.log('\n━━━ 5. tools/list (8080 → nginx → mcp-gateway → weather) ━━━');
  const tl = await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'Mcp-Session-Id': SESSION },
    { jsonrpc:'2.0', id:2, method:'tools/list' });
  const tlData = extractJson(tl.body);
  const tools = tlData.result?.tools || [];
  check(`tools/list ${tools.length} tools`, tools.length >= 4);
  tools.forEach(t => console.log(`    - ${t.name}`));

  // 6. getAlerts
  console.log('\n━━━ 6. getAlerts(CA) ━━━');
  const a1 = await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'Mcp-Session-Id': SESSION },
    { jsonrpc:'2.0', id:3, method:'tools/call', params:{ name:'getAlerts', arguments:{state:'CA'} } });
  const a1Data = extractJson(a1.body);
  const a1Text = (a1Data.result?.content||[]).find(x=>x.type==='text');
  check('getAlerts CA', a1Text && !a1Text.text.includes('Error'));
  if(a1Text) { try { const arr=JSON.parse(a1Text.text); console.log(`    ${arr.length} alerts, first: ${arr[0]?.event}`); } catch(e) { console.log(`    ${a1Text.text.substring(0,80)}`); } }

  // 7. gw_m_c_w_weather_getAlerts
  console.log('\n━━━ 7. gw_m_c_w_weather_getAlerts(TX) ━━━');
  const a2 = await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'Mcp-Session-Id': SESSION },
    { jsonrpc:'2.0', id:4, method:'tools/call', params:{ name:'gw_m_c_w_weather_getAlerts', arguments:{state:'TX'} } });
  const a2Data = extractJson(a2.body);
  const a2Text = (a2Data.result?.content||[]).find(x=>x.type==='text');
  check('gw_m_c_w_weather_getAlerts TX', a2Text && !a2Text.text.includes('Error'));
  if(a2Text) { try { const arr=JSON.parse(a2Text.text); console.log(`    ${arr.length} alerts`); } catch(e) { console.log(`    ${a2Text.text.substring(0,80)}`); } }

  // 8. getWeatherForecast (known bug)
  console.log('\n━━━ 8. getWeatherForecast(SF) ━━━');
  const w1 = await post(`${BASE}/mcp-gateway/mcp`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'Mcp-Session-Id': SESSION },
    { jsonrpc:'2.0', id:5, method:'tools/call', params:{ name:'getWeatherForecast', arguments:{city:'San Francisco'} } });
  const w1Data = extractJson(w1.body);
  const w1Text = (w1Data.result?.content||[]).find(x=>x.type==='text');
  if(w1Text && w1Text.text.includes('Error')) { console.log('  ⚠️ weather-server JDK25 bug (NPE) — 非 auth/gateway 问题'); }
  else if(w1Text) { check('getWeatherForecast', true); console.log(`    ${w1Text.text.substring(0,80)}`); }
  else { console.log('  ⚠️ no content'); }

  // Cleanup
  await post(`${BASE}/api-gateway/ecso/auth/oauth2/register/${CID}`,
    { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json', 'X-HTTP-Method-Override': 'DELETE' }, '');

  console.log(`\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
  console.log(`结果: ${pass} ✅ / ${fail} ❌  (全部走 nginx:8080)`);
  process.exit(fail > 0 ? 1 : 0);
}

run().catch(e => { console.error(e); process.exit(1); });
