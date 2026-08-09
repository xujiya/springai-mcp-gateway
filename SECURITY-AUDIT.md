# Security Audit Report: nginx:8080 Public Endpoint

**Audit Date:** 2026-08-10 (封板 v0.13.1)  
**Target:** http://localhost:8080 (nginx)  
**Scope:** Full security audit of the single public ingress point  
**Result:** 1 High, 4 Medium, 4 Low findings (after hardening)  

---

## Executive Summary

The nginx:8080 endpoint serves as the sole public entry point, proxying to api-gateway:8081 and mcp-gateway:8082. 

**Hardening applied through v0.7.0–v0.13.1:**
- DCR denyAll → 403 JSON (no internal address leakage)
- auth-info desensitized (no clientId/redirectUri)
- Session Cookie: SameSite=Lax, Path=/api-gateway/auth, HttpOnly
- CORS restricted to localhost/127.0.0.1/null
- nginx server_tokens off, root / → 404
- Two-tier client model: DCR blocks client_credentials
- API Key dual-part model with rate limiting
- Dual SecurityFilterChain for admin paths
- MCP SDK RFC 9728 issuer validation patched

**Remaining risks:**
- Internal ports directly accessible (host network)
- No rate limiting on token endpoint
- Default admin credentials
- HMAC signing mode incomplete

---

## Complete Public Interface Catalog

| # | URL | Method | Auth | Sensitivity | Risk | Notes |
|---|-----|--------|------|-------------|------|-------|
| 1 | `/` | GET | None | 🟢 404 | LOW | Hardened: was leaking architecture |
| 2 | `/api-gateway/auth/.well-known/oauth-authorization-server` | GET | None | 🟢 Public | LOW | RFC 8414 standard |
| 3 | `/api-gateway/auth/oauth2/jwks` | GET | None | 🟢 Public | LOW | RFC 7517 standard |
| 4 | `/api-gateway/auth/oauth2/authorize` | GET/POST | Session | 🟢 Standard | LOW | |
| 5 | `/api-gateway/auth/oauth2/token` | POST | client_auth | 🟡 Standard | MEDIUM | No rate limit |
| 6 | `/api-gateway/auth/oauth2/introspect` | POST | client_auth | 🟢 Standard | LOW | |
| 7 | `/api-gateway/auth/oauth2/revoke` | POST | client_auth | 🟢 Standard | LOW | |
| 8 | `/api-gateway/auth/oauth2/register` | POST | denyAll/open | 🟢 Closed/Two-tier | LOW | DCR toggleable |
| 9 | `/api-gateway/auth/login` | GET | Session | 🟢 Login page | LOW | text/html |
| 10 | `/api-gateway/auth/login` | POST | Session | 🟡 Auth | LOW | |
| 11 | `/api-gateway/auth/oauth2/auth-info` | GET | Session | 🟢 Desensitized | LOW | No clientId/redirectUri |
| 12 | `/api-gateway/vue/` | GET | None | 🟢 Static | LOW | Vue login UI |
| 13 | `/api-gateway/admin/` | GET | None | 🟢 Static | LOW | Admin console UI |
| 14 | `/mcp-gateway/admin/login` | POST | None→Token | 🟡 Admin | MEDIUM | Default admin/admin |
| 15 | `/mcp-gateway/admin/users` | GET/POST/PUT/DELETE | Admin Token | 🟡 CRUD | MEDIUM | |
| 16 | `/mcp-gateway/admin/clients` | GET/POST/DELETE | Admin Token | 🟡 CRUD | MEDIUM | |
| 17 | `/mcp-gateway/admin/api-keys` | GET/POST/DELETE | Admin Token | 🟡 CRUD | MEDIUM | |
| 18 | `/mcp-gateway/admin/system` | GET | Admin Token | 🟢 Info | LOW | Runtime info |
| 19 | `/mcp-gateway/weather/mcp` | POST | Bearer(JWT/AK) | 🔴 MCP | LOW | Proper auth required |
| 20 | `/mcp-gateway/climate/mcp` | POST | Bearer(JWT/AK) | 🔴 MCP | LOW | Proper auth required |
| 21 | `/mcp-gateway/weather/.well-known/oauth-protected-resource` | GET | None | 🟢 RFC 9728 | LOW | |
| 22 | `/mcp-gateway/climate/.well-known/oauth-protected-resource` | GET | None | 🟢 RFC 9728 | LOW | |

---

## Findings

### 🔴 HIGH (1)

#### H3: Internal Ports Directly Accessible

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | Internal Port Exposure |
| **Status** | NOT FIXED (requires infrastructure) |

**Description:**  
Internal services bound to host-accessible ports, bypassing nginx and gateway security:
- auth-server:9090, weather-server:9092, climate-server:9093, mcp-gateway:8082

**Fix:**
```yaml
server:
  address: 127.0.0.1  # each service
```
```bash
iptables -A INPUT -p tcp --dport 9090:9099 -s !127.0.0.1 -j DROP
```

---

### 🟡 MEDIUM (4)

#### M5: Token Endpoint No Rate Limiting

**Risk:** Brute force client_secret  
**Fix:** nginx `limit_req_zone`:
```nginx
limit_req_zone $binary_remote_addr zone=token:10m rate=5r/m;
```

#### M6: Default Admin Credentials

**Risk:** admin/admin in sys_user  
**Fix:** Change in production MySQL data.sql

#### M7: HMAC Signing Mode Incomplete

**Risk:** Only bcrypt hash stored, cannot verify HMAC server-side  
**Fix:** AES-GCM encrypted secret storage column

#### M8: cookie.secure: false

**Risk:** Cookie sent over HTTP  
**Fix:** `cookie.secure: true` when HTTPS configured

---

### 🟢 LOW (4)

#### L1: JWT Payload Decodable
Standard JWT design, RS256 signature prevents tampering. Acceptable.

#### L2: JWKS Endpoint Public
RFC 7517 standard, public key is meant to be public. Acceptable.

#### L3: AS Metadata Exposes Auth Methods
RFC 8414 requirement, helps clients. Acceptable.

#### L4: Vue 500 Error Includes requestId
Useful for debugging, no internal info. Acceptable.

---

## Security Hardening Applied (v0.7.0 – v0.13.1)

| ID | Finding | Fix | Version |
|----|---------|-----|---------|
| H1 | 302 Location leaks localhost:9090 | denyAll → 403 JSON | v0.7.0 |
| H2 | auth-info leaks clientId/redirectUri | Desensitized response | v0.7.0 |
| M1 | Cookie no Secure/SameSite/Path | SameSite=Lax + Path=/api-gateway/auth | v0.7.0 |
| M2 | CORS allows * | Restricted to localhost/127.0.0.1/null | v0.7.0 |
| M3 | nginx version leaked | server_tokens off | v0.7.0 |
| M4 | Root / leaks architecture | → 404 | v0.7.0 |
| M5' | DCR allows client_credentials | Two-tier client model | v0.3.0 |
| — | API Key guessable | Dual-part + strong random | v0.11.0 |
| — | API Key no brute-force protection | Rate limiting (10→5min) | v0.11.0 |
| — | Admin token plaintext | bcrypt comparison | v0.11.0 |
| — | permitAll + JWT filter conflict | Dual SecurityFilterChain | v0.12.1 |
| — | CORS Origin:null blocked | Added "null" to patterns | v0.9.0 |
| — | MCP SDK issuer mismatch | Patch @modelcontextprotocol/client | v0.13.1 |

---

## Production Deployment Checklist

- [ ] **TLS**: HTTPS on nginx with valid certificate
- [ ] **Bind internal**: `server.address: 127.0.0.1` for all services
- [ ] **Firewall**: Block external access to ports 9090-9099
- [ ] **DCR disabled**: `mcp.dcr.enabled: false`
- [ ] **Admin password**: Change default admin/admin
- [ ] **bcrypt admin-token-hash**: Replace plaintext admin-token
- [ ] **cookie.secure: true**
- [ ] **CORS**: Restrict to production domain only
- [ ] **Rate limiting**: nginx `limit_req_zone` for /oauth2/token
- [ ] **AES-GCM**: Encrypted secret storage for HMAC signing
- [ ] **SDK patch**: Re-apply MCP SDK issuer patch after any npm upgrade
- [ ] **nginx**: Review and restrict root location
- [ ] **Monitoring**: Log all 401/403 events, alert on rate limit triggers

---

## Summary

| Level | Count | Details |
|-------|-------|---------|
| 🔴 HIGH | 1 | H3: Internal ports accessible (infra fix) |
| 🟡 MEDIUM | 4 | M5-M8: Rate limit, default creds, HMAC, cookie.secure |
| 🟢 LOW | 4 | L1-L4: Standard behavior |

**Overall posture**: Significantly improved from initial audit (6 HIGH → 1 HIGH). Remaining issues require infrastructure changes (network binding, firewall) or production configuration (credentials, TLS, rate limiting).
