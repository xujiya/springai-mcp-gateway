const http = require('http');

function req(method, path, body, extra={}) {
  return new Promise((y, n) => {
    const d = body ? JSON.stringify(body) : '';
    const opts = {
      hostname:'localhost', port:8080, path, method,
      headers: {
        'Content-Type':'application/json',
        'Accept':'application/json, text/event-stream',
        ...extra,
        ...(d ? {'Content-Length':Buffer.byteLength(d)} : {})
      }
    };
    const r = http.request(opts, res => {
      let b=''; res.on('data',c=>b+=c); res.on('end',()=>y({s:res.statusCode,h:res.headers,b}));
    });
    r.on('error',n); if(d) r.write(d); r.end();
  });
}

function parseSSE(b) {
  return b.split('\n').filter(l=>l.startsWith('data:')).map(l=>{try{return JSON.parse(l.substring(5).trim())}catch(e){return null}}).filter(Boolean);
}

function formEncode(obj) {
  return Object.entries(obj).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&');
}

async function step(label, fn) {
  try { const r = await fn(); console.log('  ✅', label); return r; }
  catch(e) { console.log('  ❌', label, e.message); return null; }
}

(async()=>{
  console.log('╔══════════════════════════════════════════════════════════╗');
  console.log('║     DCR 开启模式 — 完整 MCP 验证 (nginx:8080)          ║');
  console.log('╚══════════════════════════════════════════════════════════╝');
  console.log('');

  // Step 1: AS Metadata
  console.log('─── RFC 8414: AS Discovery ───');
  const asMeta = await step('GET /.well-known/oauth-authorization-server', async()=>{
    const r = await req('GET','/api-gateway/ecso/auth/.well-known/oauth-authorization-server');
    const d = JSON.parse(r.b);
    if (!d.registration_endpoint) throw new Error('no registration_endpoint');
    if (!d.token_endpoint) throw new Error('no token_endpoint');
    return d;
  });
  console.log('    registration_endpoint:', asMeta?.registration_endpoint);
  console.log('');

  // Step 2: DCR Register
  console.log('─── RFC 9728: DCR Register (public PKCE client) ───');
  const dcr = await step('POST /oauth2/register → 201', async()=>{
    const r = await req('POST','/api-gateway/ecso/auth/oauth2/register',{
      client_name:'mcp-dcr-test-client',
      redirect_uris:['http://localhost:19876/callback'],
      grant_types:['authorization_code','refresh_token'],
      token_endpoint_auth_method:'none',
      scope:'mcp:read mcp:write'
    });
    if (r.s !== 201) throw new Error('HTTP '+r.s+': '+r.b);
    return JSON.parse(r.b);
  });
  console.log('    client_id:', dcr?.client_id?.substring(0,24)+'...');
  console.log('    grant_types:', dcr?.grant_types);
  console.log('    auth_method:', dcr?.token_endpoint_auth_method);
  console.log('');

  // Step 3: PRM
  console.log('─── RFC 9728: Protected Resource Metadata ───');
  const prm = await step('GET /weather/.well-known/oauth-protected-resource', async()=>{
    const r = await req('GET','/mcp-gateway/weather/.well-known/oauth-protected-resource');
    const d = JSON.parse(r.b);
    if (!d.authorization_servers?.length) throw new Error('no authorization_servers');
    return d;
  });
  console.log('    resource:', prm?.resource);
  console.log('');

  // Step 4: 401
  console.log('─── 401 + per-service WWW-Authenticate ───');
  const www = await step('POST /weather/mcp (no token) → 401', async()=>{
    const r = await req('POST','/mcp-gateway/weather/mcp',{jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'test',version:'1.0'}}});
    if (r.s !== 401) throw new Error('HTTP '+r.s);
    const w = r.h['www-authenticate'];
    if (!w?.includes('weather')) throw new Error('no per-service WWW-Authenticate: '+w);
    return w;
  });
  console.log('    WWW-Authenticate:', www?.substring(0,80)+'...');
  console.log('');

  // Step 5: Token (client_credentials for quick test)
  console.log('─── Token Acquisition ───');
  const tokenData = await step('POST /oauth2/token → 200', async()=>{
    const body = formEncode({grant_type:'client_credentials',scope:'mcp:read mcp:write'});
    const r = await new Promise((y,n)=>{
      const opts = {
        hostname:'localhost',port:8080,
        path:'/api-gateway/ecso/auth/oauth2/token',
        method:'POST',
        headers:{
          'Content-Type':'application/x-www-form-urlencoded',
          'Authorization':'Basic '+Buffer.from('springai-gateway-client:secret').toString('base64')
        }
      };
      const rr = http.request(opts, res=>{let b='';res.on('data',c=>b+=c);res.on('end',()=>y({s:res.statusCode,b}));});
      rr.on('error',n); rr.write(body); rr.end();
    });
    if (r.s!==200) throw new Error('HTTP '+r.s+': '+r.b);
    return JSON.parse(r.b);
  });
  const TOKEN = tokenData?.access_token;
  console.log('    expires_in:', tokenData?.expires_in);
  console.log('');

  // Step 6-10: Weather MCP
  console.log('─── Weather MCP Service ───');
  const initR = await req('POST','/mcp-gateway/weather/mcp',
    {jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'dcr-test',version:'1.0'}}},
    {'Authorization':'Bearer '+TOKEN}
  );
  const initParsed = parseSSE(initR.b)[0];
  const SID = initR.h['mcp-session-id'];
  await step('initialize → serverInfo: '+initParsed?.result?.serverInfo?.name, async()=>{ if (!SID) throw new Error('no session'); return SID; });
  console.log('    session:', SID?.substring(0,16)+'...');

  await req('POST','/mcp-gateway/weather/mcp',
    {jsonrpc:'2.0',method:'notifications/initialized'},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':SID}
  );

  const toolsR = await req('POST','/mcp-gateway/weather/mcp',
    {jsonrpc:'2.0',id:2,method:'tools/list'},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':SID}
  );
  const tools = parseSSE(toolsR.b)[0]?.result?.tools||[];
  await step('tools/list → '+tools.map(t=>t.name).join(', '), async()=>{ if (!tools.length) throw new Error('no tools'); });

  const alertR = await req('POST','/mcp-gateway/weather/mcp',
    {jsonrpc:'2.0',id:3,method:'tools/call',params:{name:'getAlerts',arguments:{state:'WA'}}},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':SID}
  );
  const alertText = parseSSE(alertR.b)[0]?.result?.content?.[0]?.text||'';
  await step('getAlerts(WA) → Alert data', async()=>{ if (!alertText) throw new Error('no data'); });

  const fcstR = await req('POST','/mcp-gateway/weather/mcp',
    {jsonrpc:'2.0',id:4,method:'tools/call',params:{name:'getWeatherForecast',arguments:{latitude:47.6,longitude:-122.3}}},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':SID}
  );
  const fcstText = parseSSE(fcstR.b)[0]?.result?.content?.[0]?.text||'';
  await step('getWeatherForecast → Forecast data', async()=>{ if (!fcstText) throw new Error('no data'); });
  console.log('');

  // Step 11: Climate MCP
  console.log('─── Climate MCP Service ───');
  const cInitR = await req('POST','/mcp-gateway/climate/mcp',
    {jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'dcr-test',version:'1.0'}}},
    {'Authorization':'Bearer '+TOKEN}
  );
  const CSID = cInitR.h['mcp-session-id'];
  await step('climate initialize', async()=>{ if (!CSID) throw new Error('no session'); });

  await req('POST','/mcp-gateway/climate/mcp',
    {jsonrpc:'2.0',method:'notifications/initialized'},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':CSID}
  );

  const cToolsR = await req('POST','/mcp-gateway/climate/mcp',
    {jsonrpc:'2.0',id:2,method:'tools/list'},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':CSID}
  );
  const cTools = parseSSE(cToolsR.b)[0]?.result?.tools||[];
  await step('tools/list → '+cTools.map(t=>t.name).join(', '), async()=>{ if (!cTools.length) throw new Error('no tools'); });

  const stormR = await req('POST','/mcp-gateway/climate/mcp',
    {jsonrpc:'2.0',id:3,method:'tools/call',params:{name:'getStormWarnings',arguments:{state:'FL'}}},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':CSID}
  );
  const stormText = parseSSE(stormR.b)[0]?.result?.content?.[0]?.text||'';
  await step('getStormWarnings(FL) → Storm data', async()=>{ if (!stormText) throw new Error('no data'); });

  const climR = await req('POST','/mcp-gateway/climate/mcp',
    {jsonrpc:'2.0',id:4,method:'tools/call',params:{name:'getClimateForecast',arguments:{latitude:34.0,longitude:-118.2}}},
    {'Authorization':'Bearer '+TOKEN,'Mcp-Session-Id':CSID}
  );
  const climText = parseSSE(climR.b)[0]?.result?.content?.[0]?.text||'';
  await step('getClimateForecast → Climate data', async()=>{ if (!climText) throw new Error('no data'); });
  console.log('');

  // Step 12: DCR two-tier security check
  console.log('─── DCR 两层客户端模型安全检查 ───');
  const dcr2 = await step('DCR 申请 client_credentials → 被过滤', async()=>{
    const r = await req('POST','/api-gateway/ecso/auth/oauth2/register',{
      client_name:'attacker-client',
      redirect_uris:['http://localhost:9999/callback'],
      grant_types:['client_credentials'],
      scope:'mcp:read mcp:write'
    });
    const d = JSON.parse(r.b);
    if (d.grant_types?.includes('client_credentials')) throw new Error('安全漏洞: DCR返回了client_credentials!');
    return d.grant_types;
  });
  console.log('    attacker grant_types after filter:', dcr2);
  console.log('');

  console.log('╔══════════════════════════════════════════════════════════╗');
  console.log('║            DCR 开启模式验证完毕 ✅                      ║');
  console.log('╚══════════════════════════════════════════════════════════╝');
})();
