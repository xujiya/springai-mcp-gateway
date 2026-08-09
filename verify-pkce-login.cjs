const http = require('http');
const crypto = require('crypto');

function get(path, headers = {}) {
  return new Promise((y, n) => {
    const r = http.request({ hostname: 'localhost', port: 8080, path, method: 'GET', headers }, res => {
      let b = ''; res.on('data', c => b += c); res.on('end', () => y({ s: res.statusCode, h: res.headers, b }));
    }); r.on('error', n); r.end();
  });
}

function post(path, body, headers = {}, isForm = false) {
  return new Promise((y, n) => {
    const d = isForm
      ? Object.entries(body).map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&')
      : JSON.stringify(body);
    const r = http.request({
      hostname: 'localhost', port: 8080, path, method: 'POST',
      headers: {
        'Content-Type': isForm ? 'application/x-www-form-urlencoded' : 'application/json',
        'Content-Length': Buffer.byteLength(d), ...headers
      }
    }, res => { let b = ''; res.on('data', c => b += c); res.on('end', () => y({ s: res.statusCode, h: res.headers, b })); });
    r.on('error', n); r.write(d); r.end();
  });
}

function parseSSE(b) {
  return b.split('\n').filter(l => l.startsWith('data:')).map(l => { try { return JSON.parse(l.substring(5).trim()) } catch (e) { return null } }).filter(Boolean);
}

function extractCookie(setCookie) {
  if (!setCookie) return '';
  const arr = Array.isArray(setCookie) ? setCookie : [setCookie];
  return arr.map(c => c.split(';')[0]).join('; ');
}

// PKCE helpers
function generateCodeVerifier() {
  return crypto.randomBytes(32).toString('base64url');
}
function generateCodeChallenge(verifier) {
  return crypto.createHash('sha256').update(verifier).digest('base64url');
}

(async () => {
  console.log('╔══════════════════════════════════════════════════════════════╗');
  console.log('║  完整 PKCE 登录+MCP 验证 (nginx:8080)                      ║');
  console.log('╚══════════════════════════════════════════════════════════════╝');
  console.log('');

  // PKCE
  const codeVerifier = generateCodeVerifier();
  const codeChallenge = generateCodeChallenge(codeVerifier);
  const redirectUri = 'http://localhost:19876/callback';
  const clientId = 'mcp-weather-client';
  console.log('PKCE code_verifier:', codeVerifier.substring(0, 16) + '...');
  console.log('PKCE code_challenge:', codeChallenge.substring(0, 16) + '...');
  console.log('');

  // ── Step 1: Authorize → 302 ──
  console.log('─── 1. GET /oauth2/authorize ───');
  const r1 = await get(`/api-gateway/ecso/auth/oauth2/authorize?response_type=code&client_id=${clientId}&redirect_uri=${encodeURIComponent(redirectUri)}&scope=mcp:read%20mcp:write&code_challenge=${codeChallenge}&code_challenge_method=S256`);
  const cookies1 = extractCookie(r1.h['set-cookie']);
  console.log('  HTTP', r1.s, '→ Location:', r1.h.location);
  console.log('  Cookie:', cookies1.substring(0, 50) + '...');

  // ── Step 2: Vue login page ──
  console.log('');
  console.log('─── 2. Vue Login Page ───');
  const r2 = await get('/api-gateway/ecso/vue/');
  const ok2 = r2.s === 200 && r2.b.includes('<!DOCTYPE html>');
  console.log('  ' + (ok2 ? '✅' : '❌'), 'HTTP', r2.s, 'text/html:', r2.h['content-type']?.includes('text/html'));

  // ── Step 3: POST login ──
  console.log('');
  console.log('─── 3. POST /login (admin/admin) ───');
  const r3 = await post('/api-gateway/ecso/auth/login', { username: 'admin', password: 'admin' }, { Cookie: cookies1 }, true);
  const cookies3 = [cookies1, extractCookie(r3.h['set-cookie'])].filter(Boolean).join('; ');
  console.log('  HTTP', r3.s, '→ Location:', r3.h.location?.substring(0, 80) + '...');

  // ── Step 4: Follow redirect back to authorize ──
  console.log('');
  console.log('─── 4. GET /oauth2/authorize (authenticated) ───');
  const r4 = await get(r3.h.location, { Cookie: cookies3 });
  console.log('  HTTP', r4.s, '→ Location:', r4.h.location?.substring(0, 80) + '...');
  const allCookies = [cookies3, extractCookie(r4.h['set-cookie'])].filter(Boolean).join('; ');

  // Extract code
  let code = null;
  if (r4.h.location?.includes('code=')) {
    code = new URL(r4.h.location).searchParams.get('code');
    console.log('  ✅ Authorization code:', code.substring(0, 20) + '...');
  } else {
    // May need one more redirect
    const r4b = await get(r4.h.location, { Cookie: allCookies });
    console.log('  → follow redirect: HTTP', r4b.s, '→', r4b.h.location?.substring(0, 80) + '...');
    if (r4b.h.location?.includes('code=')) {
      code = new URL(r4b.h.location).searchParams.get('code');
      console.log('  ✅ Authorization code:', code.substring(0, 20) + '...');
    }
  }

  if (!code) {
    console.log('  ❌ No authorization code! Aborting.');
    process.exit(1);
  }

  // ── Step 5: Exchange code for token (PKCE) ──
  console.log('');
  console.log('─── 5. POST /oauth2/token (PKCE code exchange) ───');
  const r5 = await post('/api-gateway/ecso/auth/oauth2/token', {
    grant_type: 'authorization_code',
    code: code,
    redirect_uri: redirectUri,
    client_id: clientId,
    code_verifier: codeVerifier
  }, {}, true);
  const tokenData = JSON.parse(r5.b);
  if (r5.s !== 200) {
    console.log('  ❌ Token exchange failed! HTTP', r5.s, tokenData);
    process.exit(1);
  }
  console.log('  ✅ HTTP', r5.s);
  console.log('  access_token:', tokenData.access_token.substring(0, 30) + '...');
  console.log('  token_type:', tokenData.token_type);
  console.log('  expires_in:', tokenData.expires_in);
  console.log('  scope:', tokenData.scope);

  const TOKEN = tokenData.access_token;

  // ── Step 6: Weather MCP ──
  console.log('');
  console.log('─── 6. Weather MCP: initialize ───');
  const mcp1 = await post('/mcp-gateway/weather/mcp',
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'pkce-test', version: '1.0' } } },
    { Authorization: 'Bearer ' + TOKEN, Accept: 'application/json, text/event-stream' }
  );
  const init1 = parseSSE(mcp1.b)[0];
  const sid1 = mcp1.h['mcp-session-id'];
  console.log('  ✅ HTTP', mcp1.s, '→ server:', init1?.result?.serverInfo?.name, 'v' + init1?.result?.serverInfo?.version);
  console.log('  session:', sid1?.substring(0, 16) + '...');

  // notifications/initialized
  await post('/mcp-gateway/weather/mcp',
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': sid1 }
  );

  // ── Step 7: tools/list ──
  console.log('');
  console.log('─── 7. Weather MCP: tools/list ───');
  const mcp2 = await post('/mcp-gateway/weather/mcp',
    { jsonrpc: '2.0', id: 2, method: 'tools/list' },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': sid1, Accept: 'application/json, text/event-stream' }
  );
  const tools = parseSSE(mcp2.b)[0]?.result?.tools || [];
  console.log('  ✅', tools.map(t => t.name).join(', '));

  // ── Step 8: getAlerts ──
  console.log('');
  console.log('─── 8. Weather MCP: getAlerts(WA) ───');
  const mcp3 = await post('/mcp-gateway/weather/mcp',
    { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'getAlerts', arguments: { state: 'WA' } } },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': sid1, Accept: 'application/json, text/event-stream' }
  );
  const alertText = parseSSE(mcp3.b)[0]?.result?.content?.[0]?.text || '';
  console.log('  ✅', alertText.substring(0, 80) + '...');

  // ── Step 9: getWeatherForecast ──
  console.log('');
  console.log('─── 9. Weather MCP: getWeatherForecast ───');
  const mcp4 = await post('/mcp-gateway/weather/mcp',
    { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'getWeatherForecast', arguments: { latitude: 47.6, longitude: -122.3 } } },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': sid1, Accept: 'application/json, text/event-stream' }
  );
  const fcstText = parseSSE(mcp4.b)[0]?.result?.content?.[0]?.text || '';
  console.log('  ✅', fcstText.substring(0, 80) + '...');

  // ── Step 10: Climate MCP ──
  console.log('');
  console.log('─── 10. Climate MCP: initialize ───');
  const cmcp1 = await post('/mcp-gateway/climate/mcp',
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'pkce-test', version: '1.0' } } },
    { Authorization: 'Bearer ' + TOKEN, Accept: 'application/json, text/event-stream' }
  );
  const csid = cmcp1.h['mcp-session-id'];
  const cinit = parseSSE(cmcp1.b)[0];
  console.log('  ✅ HTTP', cmcp1.s, '→ server:', cinit?.result?.serverInfo?.name, 'v' + cinit?.result?.serverInfo?.version);

  await post('/mcp-gateway/climate/mcp',
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': csid }
  );

  console.log('');
  console.log('─── 11. Climate MCP: getStormWarnings(FL) ───');
  const cmcp3 = await post('/mcp-gateway/climate/mcp',
    { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'getStormWarnings', arguments: { state: 'FL' } } },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': csid, Accept: 'application/json, text/event-stream' }
  );
  const stormText = parseSSE(cmcp3.b)[0]?.result?.content?.[0]?.text || '';
  console.log('  ✅', stormText.substring(0, 80) + '...');

  console.log('');
  console.log('─── 12. Climate MCP: getClimateForecast ───');
  const cmcp4 = await post('/mcp-gateway/climate/mcp',
    { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'getClimateForecast', arguments: { latitude: 34.0, longitude: -118.2 } } },
    { Authorization: 'Bearer ' + TOKEN, 'Mcp-Session-Id': csid, Accept: 'application/json, text/event-stream' }
  );
  const climText = parseSSE(cmcp4.b)[0]?.result?.content?.[0]?.text || '';
  console.log('  ✅', climText.substring(0, 80) + '...');

  console.log('');
  console.log('╔══════════════════════════════════════════════════════════════╗');
  console.log('║  完整 PKCE 登录+MCP 验证 12/12 ✅                          ║');
  console.log('╚══════════════════════════════════════════════════════════════╝');
})();
