package es.omarall.mcp.gateway.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import es.omarall.mcp.gateway.entity.SysUser;
import es.omarall.mcp.gateway.entity.RegisteredClientEntity;
import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.gateway.mapper.SysUserMapper;
import es.omarall.mcp.gateway.mapper.RegisteredClientMapper;
import es.omarall.mcp.gateway.mapper.ApiKeyMapper;
import es.omarall.mcp.gateway.service.ApiKeyService;

/**
 * 管理后台统一 API：登录 + 用户管理 + OAuth客户端管理 + API Key管理
 * <p>
 * 不影响现有 DCR + OAuth PKCE 流程，只共用 MySQL 数据库。
 * <p>
 * 认证方式：
 * <ul>
 *   <li>登录：POST /admin/login 用 sys_user 验证，返回 admin token</li>
 *   <li>其他 API：Bearer adm-xxx，controller 自验</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminConsoleController {

    private final SysUserMapper userMapper;
    private final RegisteredClientMapper clientMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder passwordEncoder;
    private final String adminTokenHash;
    private final String adminTokenPlaintext;
    private final int mcpServiceCount;
    private final boolean dcrEnabled;
    private final String accessTokenTTL;

    public AdminConsoleController(
            SysUserMapper userMapper,
            RegisteredClientMapper clientMapper,
            ApiKeyMapper apiKeyMapper,
            ApiKeyService apiKeyService,
            PasswordEncoder passwordEncoder,
            @Value("${ecso.mcp.api-key.admin-token-hash:}") String adminTokenHash,
            @Value("${ecso.mcp.api-key.admin-token:}") String adminTokenPlaintext,
            @Value("${ecso.mcp.services.weather.url:}") String weatherUrl,
            @Value("${ecso.mcp.services.climate.url:}") String climateUrl,
            @Value("${ecso.auth.dcr.enabled:true}") boolean dcrEnabled,
            @Value("${ecso.auth.dcr.access-token-time-to-live:24h}") String accessTokenTTL) {
        this.userMapper = userMapper;
        this.clientMapper = clientMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.apiKeyService = apiKeyService;
        this.passwordEncoder = passwordEncoder;
        this.adminTokenPlaintext = adminTokenPlaintext;
        this.mcpServiceCount = (weatherUrl.isBlank() ? 0 : 1) + (climateUrl.isBlank() ? 0 : 1);
        this.dcrEnabled = dcrEnabled;
        this.accessTokenTTL = accessTokenTTL;

        if (!adminTokenHash.isBlank()) {
            this.adminTokenHash = adminTokenHash;
        } else if (!adminTokenPlaintext.isBlank()) {
            this.adminTokenHash = passwordEncoder.encode(adminTokenPlaintext);
        } else {
            this.adminTokenHash = "";
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 登录 — sys_user 验证
    // ═══════════════════════════════════════════════════════════

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return badRequest("缺少 username 或 password");
        }

        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) return unauthorized("\u7528\u6237\u540d\u6216\u5bc6\u7801\u9519\u8bef");

        // DelegatingPasswordEncoder \u9700\u8981 {bcrypt} \u524d\u7f00\u6765\u5206\u6d3e\n        if (!passwordEncoder.matches(password, user.getPassword())) return unauthorized("\u7528\u6237\u540d\u6216\u5bc6\u7801\u9519\u8bef");
        if (!Boolean.TRUE.equals(user.getEnabled())) return forbidden("账号已禁用");

        // 登录成功，返回 admin token
        if (adminTokenPlaintext.isBlank()) return serverError("未配置 admin token");

        log.info("管理后台登录: username={}", username);
        return ResponseEntity.ok(Map.of(
                "username", username,
                "adminToken", adminTokenPlaintext,
                "message", "登录成功"
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // 用户管理 CRUD
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listUsers(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        List<Map<String, Object>> result = userMapper.selectList(null).stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("enabled", u.getEnabled());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createUser(@RequestHeader("Authorization") String auth,
                                         @RequestBody Map<String, Object> body) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        String username = (String) body.get("username");
        String password = (String) body.get("password");
        if (username == null || password == null) return badRequest("缺少 username 或 password");

        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "用户名已存在"));

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("{bcrypt}" + passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        userMapper.insert(user);

        log.info("Admin 创建用户: {}", username);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", user.getId(), "username", username));
    }

    @PutMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateUser(@RequestHeader("Authorization") String auth,
                                         @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();

        if (body.containsKey("username")) user.setUsername((String) body.get("username"));
        if (body.containsKey("password")) user.setPassword("{bcrypt}" + passwordEncoder.encode((String) body.get("password")));
        if (body.containsKey("enabled")) user.setEnabled((Boolean) body.get("enabled"));
        userMapper.updateById(user);

        return ResponseEntity.ok(Map.of("id", id, "username", user.getUsername()));
    }

    @DeleteMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteUser(@RequestHeader("Authorization") String auth,
                                         @PathVariable Long id) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();
        if ("admin".equals(user.getUsername())) return forbidden("不能删除 admin 用户");

        userMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    // ═══════════════════════════════════════════════════════════
    // OAuth 客户端 CRUD
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listClients(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        List<Map<String, Object>> result = clientMapper.selectList(null).stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("clientId", c.getClientId());
            m.put("clientName", c.getClientName());
            m.put("clientAuthenticationMethods", c.getClientAuthenticationMethods());
            m.put("authorizationGrantTypes", c.getAuthorizationGrantTypes());
            m.put("redirectUris", c.getRedirectUris());
            m.put("scopes", c.getScopes());
            m.put("hasSecret", c.getClientSecret() != null && !c.getClientSecret().isBlank());
            m.put("clientIdIssuedAt", c.getClientIdIssuedAt() != null ? c.getClientIdIssuedAt().toString() : null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createClient(@RequestHeader("Authorization") String auth,
                                           @RequestBody Map<String, Object> body) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        String clientId = (String) body.get("clientId");
        String clientName = (String) body.getOrDefault("clientName", clientId);
        if (clientId == null) return badRequest("缺少 clientId");

        RegisteredClientEntity existing = clientMapper.selectOne(
                new LambdaQueryWrapper<RegisteredClientEntity>().eq(RegisteredClientEntity::getClientId, clientId));
        if (existing != null) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "clientId 已存在"));

        String clientSecret = (String) body.get("clientSecret");
        boolean isPublic = clientSecret == null || clientSecret.isBlank();

        RegisteredClientEntity client = new RegisteredClientEntity();
        client.setId(clientId);
        client.setClientId(clientId);
        client.setClientName(clientName);
        client.setClientIdIssuedAt(Instant.now());
        if (!isPublic) client.setClientSecret("{bcrypt}" + passwordEncoder.encode(clientSecret));
        client.setClientAuthenticationMethods(isPublic ? "[\"none\"]" : "[\"client_secret_basic\",\"client_secret_post\"]");
        client.setAuthorizationGrantTypes(
                body.get("authorizationGrantTypes") != null ? (String) body.get("authorizationGrantTypes")
                        : "[\"authorization_code\",\"refresh_token\"]");
        client.setRedirectUris((String) body.getOrDefault("redirectUris", "[]"));
        client.setScopes((String) body.getOrDefault("scopes", "[\"mcp:read\",\"mcp:write\"]"));
        client.setClientSettings((String) body.getOrDefault("clientSettings",
                "{\"settings.client.require-proof-key\":" + isPublic + ",\"settings.client.require-authorization-consent\":false}"));
        client.setTokenSettings((String) body.getOrDefault("tokenSettings",
                "{\"settings.token.reuse-refresh-tokens\":true}"));

        clientMapper.insert(client);
        log.info("Admin 创建 OAuth 客户端: clientId={}, public={}", clientId, isPublic);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "clientId", clientId, "clientName", clientName,
                "clientType", isPublic ? "public (PKCE)" : "confidential"));
    }

    @DeleteMapping(value = "/clients/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteClient(@RequestHeader("Authorization") String auth,
                                           @PathVariable String clientId) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        RegisteredClientEntity client = clientMapper.selectOne(
                new LambdaQueryWrapper<RegisteredClientEntity>().eq(RegisteredClientEntity::getClientId, clientId));
        if (client == null) return ResponseEntity.notFound().build();
        if ("springai-gateway-client".equals(clientId)) return forbidden("不能删除系统内置客户端");

        clientMapper.deleteById(client.getId());
        log.info("Admin 删除 OAuth 客户端: {}", clientId);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    // ═══════════════════════════════════════════════════════════
    // API Key CRUD（复用已有 ApiKeyService + ApiKeyAdminController）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listApiKeys(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        List<Map<String, Object>> result = apiKeyMapper.selectList(null).stream().map(k -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("accessKeyId", k.getAccessKeyId());
            m.put("accessKeyPrefix", k.getAccessKeyPrefix());
            m.put("serviceScope", k.getServiceScope());
            m.put("description", k.getDescription());
            m.put("createdBy", k.getCreatedBy());
            m.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : null);
            m.put("expiresAt", k.getExpiresAt() != null ? k.getExpiresAt().toString() : "never");
            m.put("enabled", k.getEnabled());
            m.put("lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createApiKey(@RequestHeader("Authorization") String auth,
                                           @RequestBody Map<String, Object> body) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        Instant expiresAt = null;
        if (body.get("expiresAt") != null) {
            try { expiresAt = Instant.parse((String) body.get("expiresAt")); }
            catch (Exception e) { return badRequest("expiresAt 格式错误，用 ISO-8601"); }
        }

        ApiKeyService.CreateResult result = apiKeyService.create(
                (String) body.get("name"),
                (String) body.get("serviceScope"),
                (String) body.get("description"),
                (String) body.get("createdBy"),
                expiresAt);

        log.info("Admin 创建 API Key: name={}, id={}", body.get("name"), result.accessKeyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "accessKeyId", result.accessKeyId(),
                "accessKeySecret", result.accessKeySecret(),
                "name", body.get("name"),
                "serviceScope", body.getOrDefault("serviceScope", "*"),
                "message", "请保存 AccessKey Secret，仅显示一次！"));
    }

    @PutMapping(value = "/api-keys/{id}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> revokeApiKey(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
        return apiKeyService.revoke(id) ? ResponseEntity.ok(Map.of("message", "已吊销"))
                : ResponseEntity.notFound().build();
    }

    @PutMapping(value = "/api-keys/{id}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> enableApiKey(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
        return apiKeyService.enable(id) ? ResponseEntity.ok(Map.of("message", "已启用"))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping(value = "/api-keys/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteApiKey(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
        return apiKeyService.delete(id) ? ResponseEntity.ok(Map.of("message", "已删除"))
                : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 系统运行状态
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/system", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> systemInfo(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> info = new HashMap<>();

        // Java runtime
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("jvmName", System.getProperty("java.vm.name"));
        info.put("pid", ProcessHandle.current().pid());

        // Uptime (approximate from runtime)
        long uptimeMs = System.currentTimeMillis() - getStartTime();
        info.put("uptimeMs", uptimeMs);

        // Memory
        info.put("heapUsed", rt.totalMemory() - rt.freeMemory());
        info.put("heapMax", rt.maxMemory());
        info.put("nonHeapUsed", 0); // Computation requires ManagementFactory
        info.put("threadCount", Thread.activeCount());

        // MCP config
        info.put("mcpServiceCount", mcpServiceCount);
        info.put("dcrEnabled", dcrEnabled);
        info.put("accessTokenTTL", accessTokenTTL);

        return ResponseEntity.ok(info);
    }

    private long getStartTime() {
        try {
            // Use ProcessHandle to get start time
            return ProcessHandle.current().info().startInstant()
                    .map(i -> i.toEpochMilli())
                    .orElse(System.currentTimeMillis());
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private boolean isAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        if (adminTokenHash.isBlank()) return false;
        return passwordEncoder.matches(authHeader.substring(7), adminTokenHash);
    }

    private ResponseEntity<?> unauthorized(String msg) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", msg));
    }

    private ResponseEntity<?> forbidden(String msg) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", msg));
    }

    private ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<?> serverError(String msg) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", msg));
    }
}
