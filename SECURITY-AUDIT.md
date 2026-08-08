# Security Audit Report: nginx:8080 Public Endpoint

**Audit Date:** 2026-08-08  
**Target:** http://localhost:8080 (nginx/1.27.4)  
**Scope:** Full security audit of the single public ingress point  
**Result:** 6 High, 5 Medium, 4 Low, 3 Informational findings  

---

## Executive Summary

The nginx:8080 endpoint serves as the sole public entry point, proxying to api-gateway:8081 and mcp-gateway:8082. The architecture follows a defense-in-depth pattern with OAuth2 resource server protection on the MCP gateway. However, several critical issues were identified: internal backend ports (9090/9092/9093) are directly accessible without authentication, DCR is fully open without rate limiting, default admin credentials are active, and HTTPS is not configured. The DCR implementation correctly strips `client_credentials` grants, which is a positive security control.

---

## Findings

### Finding 1: Internal Backend Ports Directly Accessible from Host Network

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | Internal Port Exposure |
| **Status** | NOT FIXED |

**Description:**  
The internal backend services are bound to host-accessible ports and can be reached directly, bypassing nginx and all gateway-level security controls:

- **auth-server:9090** — Returns full OIDC metadata (200 OK), bypasses api-gateway rewrite filters
- **weather-server:9092** — MCP initialize succeeds without any authentication (200 OK)
- **climate-server:9093** — MCP initialize succeeds without any authentication (200 OK)

Any process on the host (or any attacker who can reach these ports via network) can interact with backends without authentication, completely bypassing the OAuth2 resource server protection on mcp-gateway:8082.

**Evidence:**
```
$ curl -s http://localhost:9090/.well-known/openid-configuration → 200 (full metadata)
$ curl -s -X POST http://localhost:9092/mcp ... → 200 (MCP initialize succeeds)
$ curl -s -X POST http://localhost:9093/mcp ... → 200 (MCP initialize succeeds)
```

**Remediation:**
1. Bind internal services to 127.0.0.1 only (not 0.0.0.0)
2. In Docker, use internal networks without port exposure to the host
3. Add firewall rules (iptables/ufw) blocking external access to 9090/9092/9093
4. Weather/climate servers should add their own authentication layer as defense-in-depth

---

### Finding 2: DCR Open Registration Without Rate Limiting or Authentication

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | DCR Open Registration |
| **Status** | NOT FIXED |

**Description:**  
The DCR endpoint `POST /api-gateway/ecso/auth/oauth2/register` is fully open (no authentication, no rate limiting). Any unauthenticated party can register arbitrary OAuth2 clients. During testing, 5+ clients were registered in rapid succession with zero friction.

Each registration creates a `client_id` + `client_secret` pair stored in the database, enabling:
- **Database flooding** — Unlimited client registrations consume storage
- **Token abuse** — Registered clients can obtain access tokens via authorization_code flow
- **Redirect URI abuse** — Clients can register any `redirect_uri` including attacker-controlled URLs

**Evidence:**
```
$ curl -X POST .../oauth2/register -d '{"client_name":"audit-test","redirect_uris":["http://evil.example/callback"],...}'
→ 200 OK, client_id + client_secret returned
```

**Remediation:**
1. Add rate limiting to the DCR endpoint (e.g., 5 registrations per minute per IP)
2. Require initial authentication token (e.g., a registration access token per RFC 7591 §3.2)
3. Validate redirect URIs against an allowlist of trusted domains
4. Add CAPTCHA or proof-of-work for registration
5. Implement automatic cleanup of unused DCR-registered clients

---

### Finding 3: Default Admin Credentials Active

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | Default Credentials |
| **Status** | NOT FIXED |

**Description:**  
The system ships with default credentials `admin/admin` (bcrypt-hashed in `data.sql`). Login succeeds and establishes an authenticated session:

**Evidence:**
```
$ curl -X POST .../login -d "username=admin&password=admin"
→ 302 Found, Set-Cookie: MCP_AUTHORIZATION_SERVER_SESSIONID=... (authenticated session)
```

**Remediation:**
1. Force password change on first login
2. Generate a random admin password at deployment time (env variable)
3. Remove default credentials from `data.sql` in production builds
4. Add account lockout after N failed attempts

---

### Finding 4: No HTTPS/TLS on Public Endpoint

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | Transport Security |
| **Status** | NOT FIXED |

**Description:**  
nginx:8080 serves plain HTTP only. HTTPS is not configured. All traffic — including OAuth2 tokens, session cookies, client secrets, and user credentials — is transmitted in cleartext.

**Evidence:**
```
$ curl -sk https://localhost:8080/ → connection refused (no TLS)
```

**Remediation:**
1. Configure TLS on nginx (port 443) with a valid certificate
2. Redirect all HTTP traffic to HTTPS
3. Add HSTS header: `Strict-Transport-Security: max-age=31536000; includeSubDomains`
4. If behind a cloud load balancer, ensure TLS termination at the edge

---

### Finding 5: CORS Configuration Allows All Origins with Credentials

| Attribute | Value |
|-----------|-------|
| **Risk** | HIGH |
| **Category** | CORS Misconfiguration |
| **Status** | NOT FIXED |

**Description:**  
Both `AuthorizationServerConfiguration.java` and `SecurityConfiguration.java` configure:
```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```

This allows any origin to make credentialed cross-origin requests. Combined with session cookies, an attacker's website can make authenticated requests to the authorization server or MCP gateway using the victim's session.

**Evidence:**
```
$ curl -X POST .../oauth2/register -H "Origin: http://evil.example"
→ Access-Control-Allow-Origin: http://evil.example
→ Access-Control-Allow-Credentials: true
```

**Remediation:**
1. Replace `*` with explicit origin allowlist: `List.of("http://localhost:8080", "https://your-domain.com")`
2. If dynamic origins are needed, implement an origin validation function
3. Never combine `allowCredentials=true` with wildcard origins in production
4. Apply to both `AuthorizationServerConfiguration` and `SecurityConfiguration`

---

### Finding 6: Nginx Version Leak in Server Header

| Attribute | Value |
|-----------|-------|
| **Risk** | MEDIUM |
| **Category** | Information Disclosure |
| **Status** | NOT FIXED |

**Description:**  
All responses include the exact nginx version: `Server: nginx/1.27.4`. This enables targeted attacks against known vulnerabilities in this specific version.

**Evidence:**
```
$ curl -sI http://localhost:8080/
→ Server: nginx/1.27.4
```

**Remediation:**
Add to nginx config: `server_tokens off;`

---

### Finding 7: Session Cookie Missing Secure and SameSite Flags

| Attribute | Value |
|-----------|-------|
| **Risk** | MEDIUM |
| **Category** | Cookie Security |
| **Status** | NOT FIXED |

**Description:**  
The session cookie `MCP_AUTHORIZATION_SERVER_SESSIONID` is set with `HttpOnly` but lacks:
- **`Secure`** flag — Cookie transmitted over HTTP, vulnerable to network sniffing
- **`SameSite`** attribute — Cookie sent on cross-site requests, enabling CSRF

**Evidence:**
```
Set-Cookie: MCP_AUTHORIZATION_SERVER_SESSIONID=...; Path=/; HttpOnly
```
(Missing: `Secure`, `SameSite=Strict` or `Lax`)

**Remediation:**
In `auth-server/application.yml`:
```yaml
server:
  servlet:
    session:
      cookie:
        secure: true      # Requires HTTPS (Finding 4)
        same-site: strict # or lax
        http-only: true   # already set
```

---

### Finding 8: Database Password in Plaintext Configuration

| Attribute | Value |
|-----------|-------|
| **Risk** | MEDIUM |
| **Category** | Credential Exposure |
| **Status** | NOT FIXED |

**Description:**  
The MySQL datasource credentials are stored in plaintext in `application.yml`:
```yaml
spring:
  datasource:
    username: root
    password: xujiya
    url: jdbc:mysql://localhost:3306/mcp_auth?useSSL=false&allowPublicKeyRetrieval=true
```

Issues:
- Plaintext password in version-controlled file
- `useSSL=false` — MySQL connection without TLS
- `allowPublicKeyRetrieval=true` — Allows MITM on connection setup
- Using `root` account — Excessive database privileges

**Remediation:**
1. Use environment variables: `password: ${DB_PASSWORD}`
2. Or use Spring Cloud Config / Vault integration
3. Remove `useSSL=false` and `allowPublicKeyRetrieval=true` in production
4. Use a dedicated database user with minimal privileges (not `root`)

---

### Finding 9: No Rate Limiting on Any Endpoint

| Attribute | Value |
|-----------|-------|
| **Risk** | MEDIUM |
| **Category** | Rate Limiting |
| **Status** | NOT FIXED |

**Description:**  
No rate limiting is configured on nginx or at the application level. Testing confirmed 10 rapid consecutive requests all returned 200 with zero throttling.

Affected critical endpoints:
- `/oauth2/token` — Brute-force client secrets
- `/oauth2/register` — Flood DCR registrations (Finding 2)
- `/login` — Brute-force credentials
- `/mcp-gateway/mcp` — MCP tool abuse

**Evidence:**
```
10 rapid requests → 200 200 200 200 200 200 200 200 200 200
```

**Remediation:**
1. Add nginx `limit_req_zone` and `limit_req` directives
2. Implement application-level rate limiting (Spring Boot + Bucket4j/Resilience4j)
3. Add progressive backoff for failed login attempts
4. Consider Cloudflare rate limiting if deployed behind CDN

---

### Finding 10: AS Metadata Advertises client_credentials Grant

| Attribute | Value |
|-----------|-------|
| **Risk** | MEDIUM |
| **Category** | Information Disclosure / Configuration |
| **Status** | NOT FIXED |

**Description:**  
The OIDC discovery metadata advertises `client_credentials` in `grant_types_supported`:
```json
"grant_types_supported": ["authorization_code", "client_credentials", "refresh_token"]
```

While DCR correctly strips `client_credentials` from registered clients (positive finding), advertising it in metadata may encourage attackers to attempt `client_credentials` flows. The pre-registered `springai-gateway-client` does have `client_credentials`, but DCR-registered clients correctly cannot obtain it.

**Evidence:**
- DCR registration with `client_credentials` → server strips it, returns only `["authorization_code", "refresh_token"]`
- Token request with `grant_type=client_credentials` by DCR client → `{"error":"unauthorized_client"}`

**Remediation:**
1. Consider removing `client_credentials` from `grant_types_supported` in AS metadata (only advertise what DCR clients can use)
2. Alternatively, keep it but ensure all pre-registered clients with `client_credentials` are tightly controlled
3. Document that `client_credentials` is only available to admin-registered clients

---

### Finding 11: CSRF Disabled on MCP Gateway

| Attribute | Value |
|-----------|-------|
| **Risk** | LOW |
| **Category** | CSRF Protection |
| **Status** | NOT FIXED |

**Description:**  
The MCP gateway explicitly disables CSRF protection:
```java
.csrf(csrf -> csrf.disable())
```

This is common for stateless API servers using Bearer token authentication, as Bearer tokens are not automatically sent by browsers (unlike cookies). However, if any session-based functionality is added to the MCP gateway in the future, this becomes a vulnerability.

**Remediation:**
1. Current state is acceptable for a pure Bearer-token API
2. Add a comment documenting the security rationale
3. If session-based auth is ever added, re-enable CSRF

---

### Finding 12: DCR Ignores Requested token_endpoint_auth_method

| Attribute | Value |
|-----------|-------|
| **Risk** | LOW |
| **Category** | DCR Configuration |
| **Status** | NOT FIXED |

**Description:**  
When requesting DCR registration with `token_endpoint_auth_method: client_secret_basic`, the server returns `client_secret_post` instead. The converter logic prioritizes `client_secret_post` and the response overwrites the requested method.

**Evidence:**
```
Request: "token_endpoint_auth_method": "client_secret_basic"
Response: "token_endpoint_auth_method": "client_secret_post"
```

**Remediation:**
1. Honor the client's requested `token_endpoint_auth_method` if it's a supported method
2. Or return an error if the requested method is not supported
3. This is a compliance issue with RFC 7591 §2 which states the server SHOULD respect the client's metadata

---

### Finding 13: DCR Client Secret Expiry May Be Too Long

| Attribute | Value |
|-----------|-------|
| **Risk** | LOW |
| **Category** | Credential Lifecycle |
| **Status** | NOT FIXED |

**Description:**  
DCR client secrets expire after 90 days (`mcp.dcr.client-secret-expires-in: 90d`). Access token TTL is 24h (`mcp.dcr.access-token-time-to-live: 24h`), which is generous. A compromised client secret remains valid for up to 90 days.

**Remediation:**
1. Reduce client secret lifetime to 30 days
2. Reduce access token TTL to 1-5 minutes for DCR clients
3. Implement client secret rotation endpoint
4. Add monitoring for anomalous token usage patterns

---

### Finding 14: Pre-registered Client Has client_credentials Grant

| Attribute | Value |
|-----------|-------|
| **Risk** | LOW |
| **Category** | Client Configuration |
| **Status** | NOT FIXED |

**Description:**  
The pre-registered `springai-gateway-client` has `client_credentials` grant type with `mcp:read` and `mcp:write` scopes. If its credentials are leaked, an attacker can obtain access tokens without user interaction. The client also has broad redirect URIs including `http://localhost:6274/oauth/callback`.

**Remediation:**
1. Rotate the `springai-gateway-client` secret regularly
2. Minimize redirect URIs to only production URLs
3. Consider removing `client_credentials` if not actively used
4. Store client secret via environment variable, not in `data.sql`

---

### Finding 15: OIDC Metadata Exposes Internal Endpoint Structure

| Attribute | Value |
|-----------|-------|
| **Risk** | INFORMATIONAL |
| **Category** | Information Disclosure |
| **Status** | NOT FIXED |

**Description:**  
The OIDC discovery document and protected resource metadata expose the full endpoint structure:
- Token, authorize, introspect, revoke, register, and JWKS URIs
- Supported grant types, scopes, and challenge methods
- MCP protected resource metadata reveals authorization server URL and resource name

This is by design per OIDC/MCP specifications but provides attackers with a complete attack surface map.

**Evidence:**
```json
{
  "introspection_endpoint": "http://localhost:8080/api-gateway/ecso/auth/oauth2/introspect",
  "revocation_endpoint": "http://localhost:8080/api-gateway/ecso/auth/oauth2/revoke",
  "registration_endpoint": "http://localhost:8080/api-gateway/ecso/auth/oauth2/register"
}
```

**Remediation:**
No action needed — this is per specification. Ensure all advertised endpoints have appropriate protection.

---

### Finding 16: MCP Endpoint Returns Proper 401 for Unauthenticated Requests

| Attribute | Value |
|-----------|-------|
| **Risk** | INFORMATIONAL |
| **Category** | MCP Endpoint Security |
| **Status** | N/A (POSITIVE) |

**Description:**  
The MCP endpoint `/mcp-gateway/mcp` correctly returns 401 Unauthorized with RFC 9728 protected resource metadata for unauthenticated requests:

```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata=http://localhost:8080/mcp-gateway/.well-known/oauth-protected-resource/mcp
```

The protected resource metadata correctly advertises the authorization server and required scopes (`mcp:read`, `mcp:write`). The `/mcp-gateway/sse` endpoint also returns 401.

**Remediation:**  
No action needed — correctly implemented per MCP and RFC 9728 specifications.

---

### Finding 17: DCR Correctly Strips client_credentials Grant

| Attribute | Value |
|-----------|-------|
| **Risk** | INFORMATIONAL |
| **Category** | DCR Security |
| **Status** | N/A (POSITIVE) |

**Description:**  
The `OAuth2ClientRegistrationRegisteredClientConverter` explicitly filters out `client_credentials` from DCR registration requests:

```java
List<String> allowedGrantTypes = clientRegistration.getGrantTypes().stream()
    .filter(grantType -> !AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(grantType))
    .toList();
```

If only `client_credentials` is requested, it falls back to `authorization_code`. Token requests with `grant_type=client_credentials` by DCR clients return `{"error":"unauthorized_client"}`.

DCR-registered clients also have `requireProofKey: true` (PKCE required), adding additional security.

**Remediation:**  
No action needed — correctly implemented.

---

### Finding 18: API-Gateway Actuator Endpoints Protected

| Attribute | Value |
|-----------|-------|
| **Risk** | INFORMATIONAL |
| **Category** | Actuator Security |
| **Status** | N/A (POSITIVE) |

**Description:**  
Spring Boot Actuator endpoints on api-gateway:8081 and mcp-gateway:8082 return 401 (require authentication). The root path `/actuator` on nginx:8080 returns the ECSO landing page (not actuator), confirming actuator is not accidentally exposed through nginx.

**Evidence:**
```
api-gateway:8081/actuator → 401
mcp-gateway:8082/actuator → 401
nginx:8080/actuator → 200 (landing page, not actuator)
```

**Remediation:**  
No action needed — actuator is properly protected.

---

## Summary Table

| # | Finding | Risk | Status |
|---|---------|------|--------|
| 1 | Internal ports (9090/9092/9093) directly accessible | HIGH | NOT FIXED |
| 2 | DCR open registration without rate limiting | HIGH | NOT FIXED |
| 3 | Default admin/admin credentials | HIGH | NOT FIXED |
| 4 | No HTTPS/TLS | HIGH | NOT FIXED |
| 5 | CORS wildcard + credentials | HIGH | NOT FIXED |
| 6 | Nginx version leak (Server header) | MEDIUM | NOT FIXED |
| 7 | Session cookie missing Secure/SameSite flags | MEDIUM | NOT FIXED |
| 8 | DB password in plaintext + useSSL=false | MEDIUM | NOT FIXED |
| 9 | No rate limiting on any endpoint | MEDIUM | NOT FIXED |
| 10 | AS metadata advertises client_credentials | MEDIUM | NOT FIXED |
| 11 | CSRF disabled on MCP gateway | LOW | NOT FIXED |
| 12 | DCR ignores requested auth method | LOW | NOT FIXED |
| 13 | DCR client secret expiry too long (90d) | LOW | NOT FIXED |
| 14 | Pre-registered client has client_credentials | LOW | NOT FIXED |
| 15 | OIDC metadata exposes endpoint structure | INFO | NOT FIXED |
| 16 | MCP endpoint proper 401 (POSITIVE) | INFO | N/A |
| 17 | DCR strips client_credentials (POSITIVE) | INFO | N/A |
| 18 | Actuator endpoints protected (POSITIVE) | INFO | N/A |

---

## Attack Surface Map

```
Internet
  │
  ▼
nginx:8080 (HTTP only, no TLS)
  ├── /                              → ECSO landing page (public)
  ├── /.well-known/oauth-authorization-server/** → RFC 8414 discovery (public)
  ├── /api-gateway/ecso/auth/
  │   ├── .well-known/openid-configuration  → OIDC metadata (public)
  │   ├── oauth2/authorize                  → Authorization endpoint (public, browser redirect)
  │   ├── oauth2/token                      → Token endpoint (public, requires client auth)
  │   ├── oauth2/register                   → DCR registration (public, NO rate limit!)
  │   ├── oauth2/jwks                       → JWKS (public)
  │   ├── oauth2/introspect                 → Introspection (requires client auth)
  │   ├── oauth2/revoke                     → Revocation (requires client auth)
  │   ├── oauth2/auth-info                  → Auth info (public, returns {pending:false})
  │   ├── login                             → Login form POST (public)
  │   └── vue/**                            → Vue login UI (public)
  ├── /mcp-gateway/
  │   ├── mcp                               → MCP endpoint (401, requires Bearer token)
  │   ├── sse                               → SSE endpoint (401, requires Bearer token)
  │   └── .well-known/oauth-protected-resource/mcp → Protected resource metadata (public)
  └── (other paths)                          → Landing page or 401

Direct host access (BYPASS nginx!):
  ├── localhost:9090  → auth-server (no gateway protection)
  ├── localhost:9091  → Vite dev server
  ├── localhost:9092  → weather-server (NO AUTH!)
  └── localhost:9093  → climate-server (NO AUTH!)
```

---

## Priority Remediation Order

1. **[P0] Bind internal ports to 127.0.0.1 / Docker internal networks** (Finding 1)
2. **[P0] Configure HTTPS on nginx** (Finding 4)
3. **[P0] Remove default admin credentials** (Finding 3)
4. **[P0] Fix CORS: replace wildcard with explicit origins** (Finding 5)
5. **[P1] Add rate limiting to DCR and token endpoints** (Finding 2, 9)
6. **[P1] Externalize DB credentials, enable SSL** (Finding 8)
7. **[P1] Add Secure + SameSite flags to session cookie** (Finding 7)
8. **[P2] Hide nginx version** (Finding 6)
9. **[P2] Review client_credentials in AS metadata** (Finding 10)
10. **[P3] Address low-risk items** (Findings 11-14)

---

*Audit performed by automated security testing against live endpoints. No code was modified during this audit.*
