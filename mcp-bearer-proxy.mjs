import http from "node:http";

const MCP_TARGET = "http://localhost:8080/mcp-gateway/mcp";
const AS_METADATA = "http://localhost:8080/api-gateway/ecso/auth/.well-known/openid-configuration";

let cachedToken = null;
let tokenExpiry = 0;
let dcrClientId = null;
let dcrClientSecret = null;

async function getAccessToken() {
  if (cachedToken && Date.now() < tokenExpiry - 10_000) return cachedToken;

  const meta = await fetchJson(AS_METADATA);
  const regEp = meta.registration_endpoint;
  const tokenEp = meta.token_endpoint;

  // Reuse DCR client if we have one
  if (!dcrClientId) {
    const dcr = await fetchJson(regEp, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        client_name: "mcp-bearer-proxy",
        redirect_uris: ["http://localhost:6274/callback"],
        token_endpoint_auth_method: "client_secret_post",
        grant_types: ["client_credentials"],
        scope: "mcp:read mcp:write",
      }),
    });
    dcrClientId = dcr.client_id;
    dcrClientSecret = dcr.client_secret;
  }

  const params = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: dcrClientId,
    client_secret: dcrClientSecret,
    scope: "mcp:read mcp:write",
    resource: "http://localhost:8080/mcp-gateway/mcp",
  });
  const tr = await fetchJson(tokenEp, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: params.toString(),
  });

  cachedToken = tr.access_token;
  tokenExpiry = Date.now() + (tr.expires_in || 300) * 1000;
  console.log(`[proxy] Token refreshed, expires_in=${tr.expires_in}s`);
  return cachedToken;
}

async function fetchJson(url, opts = {}) {
  const res = await fetch(url, opts);
  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status} from ${url}: ${t.slice(0, 200)}`);
  }
  return res.json();
}

const server = http.createServer(async (req, res) => {
  // CORS
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, GET, OPTIONS, DELETE",
      "Access-Control-Allow-Headers": "*",
    });
    return res.end();
  }

  try {
    const token = await getAccessToken();

    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);

    const fwdHeaders = {
      "Content-Type": req.headers["content-type"] || "application/json",
      Authorization: `Bearer ${token}`,
    };
    if (req.headers["accept"]) fwdHeaders["Accept"] = req.headers["accept"];
    else fwdHeaders["Accept"] = "application/json, text/event-stream";
    if (req.headers["mcp-session-id"]) fwdHeaders["Mcp-Session-Id"] = req.headers["mcp-session-id"];
    if (req.headers["last-event-id"]) fwdHeaders["Last-Event-ID"] = req.headers["last-event-id"];

    const upstream = await fetch(MCP_TARGET, {
      method: req.method,
      headers: fwdHeaders,
      body: body.length ? body : undefined,
      redirect: "manual",
    });

    const respHeaders = {};
    upstream.headers.forEach((v, k) => { respHeaders[k] = v; });
    respHeaders["Access-Control-Allow-Origin"] = "*";
    res.writeHead(upstream.status, respHeaders);

    if (upstream.body) {
      const reader = upstream.body.getReader();
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          res.write(value);
        }
      } catch {}
    }
    res.end();
  } catch (e) {
    console.error(`[proxy] ${req.method} ${req.url} Error:`, e.message);
    res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32603, message: e.message } }));
  }
});

server.listen(9099, () => console.log("[proxy] MCP Bearer Proxy on :9099 → :8080/mcp-gateway/mcp"));
