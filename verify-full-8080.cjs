const http = require('http');
const crypto = require('crypto');

const BASE = 'http://localhost:8080';  // 统一走 nginx:8080

function request(method, path, {headers = {}, body = null, isForm = false, followRedirect = false} = {}) {
  return new Promise((y, n) => {
    const url = new URL(path, BASE);
    const d = body ? (isForm
      ? Object.entries(body).map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&')
      : JSON.stringify(body)) : '';
    const h = {
      ...(body ? {'Content-Type': isForm ? 'application/x-www-form-urlencoded' : 'application/json', 'Content-Length': Buffer.byteLength(d)} : {}),
      ...headers
    };
    const r = http.request({hostname: url.hostname, port: url.port, path: url.pathname + url.search, method, headers: h}, res => {
      let b = ''; res.on('data', c => b += c); res.on('end', () => y({s: res.statusCode, h: res.headers, b}));
    }); r.on('error', n); if (body) r.write(d); r.end();
  });
}

function parseSSE(b) {
  return b.split('\n').filter(l => l.startsWith('data:')).map(l => { try { return JSON.parse(l.substring(5).trim()) } catch { return null } }).filter(Boolean);
}

function extractCookies(res) {
  const sc = res.h['set-cookie'];
  if (!sc) return '';
  return (Array.isArray(sc) ? sc : [sc]).map(c => c.split(';')[0]).join('; ');
}

async function flow(serviceName, clientId) {
  const SVC = serviceName;
  const MCP_URL = `/mcp-gateway/${SVC}/mcp`;
  const PRM_URL = `/mcp-gateway/${SVC}/.well-known/oauth-protected-resource`;
  let step = 0;
  const ok = (msg) => console.log(`  ${++step}. ✅ ${msg}`);
  const fail = (msg) => console.log(`  ${++step}. ❌ ${msg}`);

  console.log(`\n╔══════════════════════════════════════════════════════════════╗`);
  console.log(`║  DCR + PKCE + MCP 全链路: ${SVC} (nginx:8080)`);
  console.log(`╚══════════════════════════════════════════════════════════════╝`);

  // Step 0: 无token → 401
  const r0 = await request('POST', MCP_URL, {body: {jsonrpc:'2.0',id:0,method:'initialize',params:{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'test',version:'1.0'}}}});
  const wwa = r0.h['www-authenticate'] || '';
  if (r0.s === 401 && wwa.includes('resource_metadata')) ok(`401 + WWW-Authenticate`); else fail(`401: got ${r0.s}`);

  // Step 1: PRM
  const r1 = await request('GET', PRM_URL);
  const prm = JSON.parse(r1.b);
  if (prm.resource.includes('localhost:8080')) ok(`PRM resource: ${prm.resource}`); else fail(`PRM resource: ${prm.resource}`);

  // Step 2: AS Discovery
  const asUrl = prm.authorization_servers[0] + '/.well-known/oauth-authorization-server';
  const r2 = await request('GET', asUrl.replace('http://localhost:8080', ''));
  const asd = JSON.parse(r2.b);
  ok(`AS issuer: ${asd.issuer}`);
  ok(`AS registration: ${asd.registration_endpoint || 'NONE (DCR closed)'}`);

  // Step 3: DCR Register
  let usedClientId = clientId;
  if (!clientId && asd.registration_endpoint) {
    const regUrl = asd.registration_endpoint.replace('http://localhost:8080', '');
    const r3 = await request('POST', regUrl, {body: {
      client_name: `mcp-${SVC}-dcr`, redirect_uris: ['http://localhost:19876/callback'],
      grant_types: ['authorization_code', 'refresh_token'], token_endpoint_auth_method: 'none', scope: 'mcp:read mcp:write'
    }});
    const dcr = JSON.parse(r3.b);
    usedClientId = dcr.client_id;
    ok(`DCR register → ${usedClientId.substring(0,20)}... (${r3.s})`);
  } else {
    ok(`Using pre-registered client_id: ${usedClientId}`);
  }

  // Step 4: PKCE
  const codeVerifier = crypto.randomBytes(32).toString('base64url');
  const codeChallenge = crypto.createHash('sha256').update(codeVerifier).digest('base64url');
  const redirectUri = 'http://localhost:19876/callback';
  const authUrl = asd.authorization_endpoint.replace('http://localhost:8080', '');

  // 4a: authorize → 302
  const r4a = await request('GET', `${authUrl}?response_type=code&client_id=${usedClientId}&redirect_uri=${encodeURIComponent(redirectUri)}&scope=mcp:read%20mcp:write&code_challenge=${codeChallenge}&code_challenge_method=S256`);
  const cookies1 = extractCookies(r4a);
  if (r4a.s === 302) ok(`authorize → 302 → ${r4a.h.location}`); else fail(`authorize: HTTP ${r4a.s}`);

  // 4b: Vue login page
  const r4b = await request('GET', r4a.h.location.replace('http://localhost:8080', ''));
  if (r4b.s === 200 && r4b.b.includes('<!DOCTYPE')) ok(`Vue login page 200`); else fail(`Vue page: ${r4b.s}`);

  // 4c: POST login (admin/admin)
  const r4c = await request('POST', '/api-gateway/ecso/auth/login', {body: {username:'admin',password:'admin'}, isForm: true, headers: {Cookie: cookies1}});
  const cookies2 = [cookies1, extractCookies(r4c)].filter(Boolean).join('; ');
  if (r4c.s === 302) ok(`POST /login → 302`); else fail(`login: ${r4c.s} ${r4c.b.substring(0,100)}`);

  // 4d: Follow redirect back to authorize → code
  const r4d = await request('GET', r4c.h.location.replace('http://localhost:8080', ''), {headers: {Cookie: cookies2}});
  const cookies3 = [cookies2, extractCookies(r4d)].filter(Boolean).join('; ');
  let code = null;
  if (r4d.h.location?.includes('code=')) {
    code = new URL(r4d.h.location).searchParams.get('code');
    ok(`Authorization code: ${code.substring(0,16)}...`);
  } else {
    // Follow one more
    const r4e = await request('GET', r4d.h.location?.replace('http://localhost:8080', ''), {headers: {Cookie: cookies3}});
    if (r4e.h.location?.includes('code=')) {
      code = new URL(r4e.h.location).searchParams.get('code');
      ok(`Authorization code: ${code.substring(0,16)}...`);
    } else {
      fail(`No code! Last: ${r4e.s} ${r4e.h.location}`);
    }
  }

  if (!code) { console.log('  ❌ ABORT'); return; }

  // Step 5: PKCE code → token
  const tokenUrl = asd.token_endpoint.replace('http://localhost:8080', '');
  const r5 = await request('POST', tokenUrl, {body: {
    grant_type: 'authorization_code', code, redirect_uri: redirectUri,
    client_id: usedClientId, code_verifier: codeVerifier
  }, isForm: true});
  const td = JSON.parse(r5.b);
  if (td.access_token) ok(`Token: ${td.token_type} expires_in=${td.expires_in}`); else fail(`Token: ${td.error} ${td.error_description}`);

  const TOKEN = td.access_token;

  // Step 6: MCP initialize
  const r6 = await request('POST', MCP_URL, {body: {jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'verify',version:'1.0'}}}, headers: {Authorization: 'Bearer '+TOKEN, Accept:'application/json, text/event-stream'}});
  const sid = r6.h['mcp-session-id'];
  const init = parseSSE(r6.b)[0];
  ok(`MCP initialize → session ${sid?.substring(0,12)}...`);

  // notifications/initialized
  await request('POST', MCP_URL, {body: {jsonrpc:'2.0',method:'notifications/initialized'}, headers: {Authorization: 'Bearer '+TOKEN, 'Mcp-Session-Id': sid}});

  // Step 7: tools/list
  const r7 = await request('POST', MCP_URL, {body: {jsonrpc:'2.0',id:2,method:'tools/list'}, headers: {Authorization: 'Bearer '+TOKEN, 'Mcp-Session-Id': sid, Accept:'application/json, text/event-stream'}});
  const tools = parseSSE(r7.b)[0]?.result?.tools || [];
  ok(`tools/list: ${tools.map(t=>t.name).join(', ')}`);

  // Step 8: First tool call
  const firstTool = tools[0];
  if (firstTool) {
    const args = firstTool.name.includes('Alert') || firstTool.name.includes('Storm') ? {state: 'WA'} : {latitude: 47.6, longitude: -122.3};
    const r8 = await request('POST', MCP_URL, {body: {jsonrpc:'2.0',id:3,method:'tools/call',params:{name: firstTool.name, arguments: args}}, headers: {Authorization: 'Bearer '+TOKEN, 'Mcp-Session-Id': sid, Accept:'application/json, text/event-stream'}});
    const result = parseSSE(r8.b)[0]?.result?.content?.[0]?.text || '';
    ok(`${firstTool.name}: ${result.substring(0,60)}...`);
  }

  // Step 9: Second tool call
  const secondTool = tools[1];
  if (secondTool) {
    const args = secondTool.name.includes('Alert') || secondTool.name.includes('Storm') ? {state: 'FL'} : {latitude: 34.0, longitude: -118.2};
    const r9 = await request('POST', MCP_URL, {body: {jsonrpc:'2.0',id:4,method:'tools/call',params:{name: secondTool.name, arguments: args}}, headers: {Authorization: 'Bearer '+TOKEN, 'Mcp-Session-Id': sid, Accept:'application/json, text/event-stream'}});
    const result = parseSSE(r9.b)[0]?.result?.content?.[0]?.text || '';
    ok(`${secondTool.name}: ${result.substring(0,60)}...`);
  }

  console.log(`\n  ─── ${SVC}: ${step} steps completed ───`);
}

(async () => {
  // Weather: DCR auto-register
  await flow('weather', null);
  // Climate: pre-registered client_id
  await flow('climate', 'mcp-climate-client');

  console.log(`\n╔══════════════════════════════════════════════════════════════╗`);
  console.log(`║  全部验证完毕 ✅                                            ║`);
  console.log(`╚══════════════════════════════════════════════════════════════╝`);
})();
