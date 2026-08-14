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

import es.omarall.mcp.gateway.McpServiceRegistry;
import es.omarall.mcp.common.entity.SysUser;
import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.common.entity.OAuth2Client;
import es.omarall.mcp.common.mapper.SysUserMapper;
import es.omarall.mcp.gateway.mapper.ApiKeyMapper;
import es.omarall.mcp.common.mapper.OAuth2ClientMapper;
import es.omarall.mcp.gateway.service.ApiKeyService;

/**
 * 管理后台 API（mcp-gateway）。
 * <p>
 * <b>架构原则</b>：
 * <ul>
 *   <li>登录：POST /admin/login 验证 sys_user 密码，再调 auth-server client_credentials 换 JWT</li>
 *   <li>其他 API：Bearer JWT，由 Spring Security Resource Server 验签（不再自验 adm- hash）</li>
 *   <li>mcp-gateway 只持有 admin client_credentials 的 client_id/secret，不碰密码学</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminConsoleController {

    private final SysUserMapper userMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final OAuth2ClientMapper clientMapper;
    private final ApiKeyService apiKeyService;
    private final McpServiceRegistry serviceRegistry;
    private final PasswordEncoder passwordEncoder;
    private final boolean dcrEnabled;
    private final String accessTokenTTL;
    private final String mcpServerPublicUrl;

    /** auth-server client_credentials 配置 — 用于登录后换 JWT */
    private final String adminClientId;
    private final String adminClientSecret;
    private final String tokenEndpoint;

    public AdminConsoleController(
            SysUserMapper userMapper,
            ApiKeyMapper apiKeyMapper,
            OAuth2ClientMapper clientMapper,
            ApiKeyService apiKeyService,
            McpServiceRegistry serviceRegistry,
            PasswordEncoder passwordEncoder,
            @Value("${ecso.auth.dcr.enabled:true}") boolean dcrEnabled,
            @Value("${ecso.auth.dcr.access-token-time-to-live:24h}") String accessTokenTTL,
            @Value("${ecso.mcp-server.public-url:http://localhost:8080/mcp-gateway}") String mcpServerPublicUrl,
            @Value("${ecso.auth.admin-client-id:springai-gateway-client}") String adminClientId,
            @Value("${ecso.auth.admin-client-secret:secret}") String adminClientSecret,
            @Value("${ecso.auth-server.public-url:http://localhost:8080/api-gateway/ecso/auth}") String authServerUrl) {
        this.userMapper = userMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.clientMapper = clientMapper;
        this.apiKeyService = apiKeyService;
        this.serviceRegistry = serviceRegistry;
        this.passwordEncoder = passwordEncoder;
        this.dcrEnabled = dcrEnabled;
        this.accessTokenTTL = accessTokenTTL;
        this.mcpServerPublicUrl = mcpServerPublicUrl;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
        this.tokenEndpoint = authServerUrl + "/oauth2/token";
    }

    // ═══════════════════════════════════════════════════════════
    // 登录 — sys_user 验密码 → auth-server client_credentials 换 JWT
    // ═══════════════════════════════════════════════════════════

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return badRequest("缺少 username 或 password");
        }

        // 1. 验证用户密码（读 sys_user）
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) return unauthorized("用户名或密码错误");
        if (!passwordEncoder.matches(password, user.getPassword())) return unauthorized("用户名或密码错误");
        if (!Boolean.TRUE.equals(user.getEnabled())) return forbidden("账号已禁用");

        // 2. 调 auth-server client_credentials 换 JWT
        try {
            String jwt = fetchAdminJwt();
            log.info("管理后台登录: username={}, JWT obtained", username);
            return ResponseEntity.ok(Map.of(
                    "username", username,
                    "adminToken", jwt,       // 前端存为 admin_token，后续请求用 Bearer JWT
                    "tokenType", "Bearer",
                    "message", "登录成功"
            ));
        } catch (Exception e) {
            log.error("管理后台登录失败: 无法获取 JWT — {}", e.getMessage());
            return serverError("认证服务不可用: " + e.getMessage());
        }
    }

    /**
     * 调 auth-server 的 client_credentials 端点换取 JWT。
     * mcp-gateway 不做签发，只转发预注册 client 的凭据。
     */
    private String fetchAdminJwt() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        String form = java.net.URLEncoder.encode("grant_type", java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode("client_credentials", java.nio.charset.StandardCharsets.UTF_8)
                + "&" + java.net.URLEncoder.encode("client_id", java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode(adminClientId, java.nio.charset.StandardCharsets.UTF_8)
                + "&" + java.net.URLEncoder.encode("client_secret", java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode(adminClientSecret, java.nio.charset.StandardCharsets.UTF_8)
                + "&" + java.net.URLEncoder.encode("scope", java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode("mcp:read mcp:write", java.nio.charset.StandardCharsets.UTF_8);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(tokenEndpoint))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form))
                .build();

        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("auth-server 返回 HTTP " + response.statusCode() + ": " + response.body());
        }

        // 解析 JSON 拿 access_token
        String body = response.body();
        int idx = body.indexOf("\"access_token\"");
        if (idx < 0) throw new RuntimeException("响应无 access_token: " + body);
        // 简单 JSON 提取（避免引入 Jackson 的 ObjectMapper 依赖问题）
        int start = body.indexOf("\"", idx + 15) + 1;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    // ═══════════════════════════════════════════════════════════
    // OAuth2 客户端列表（只读展示）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listClients() {
        List<Map<String, Object>> result = clientMapper.selectList(null).stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", String.valueOf(c.getId()));
            m.put("clientId", c.getClientId());
            m.put("clientName", c.getClientName());
            m.put("issuedAt", c.getClientIdIssuedAt() != null ? c.getClientIdIssuedAt().toString() : null);
            m.put("grantTypes", c.getAuthorizationGrantTypes());
            m.put("scopes", c.getScopes());
            m.put("source", c.getRegistrationSource());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 用户管理（sys_user CRUD）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listUsers() {
        List<Map<String, Object>> result = userMapper.selectList(null).stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", String.valueOf(u.getId()));
            m.put("username", u.getUsername());
            m.put("roles", u.getRoles());
            m.put("enabled", u.getEnabled());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        if (username == null || password == null) return badRequest("缺少 username 或 password");

        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "用户名已存在"));

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(normalizeRoles(body.get("roles")));
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        userMapper.insert(user);
        log.info("Admin 创建用户: username={}, roles={}", username, user.getRoles());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", String.valueOf(user.getId()), "username", username, "roles", user.getRoles()));
    }

    @PutMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();
        if (body.containsKey("username")) user.setUsername((String) body.get("username"));
        if (body.containsKey("password")) user.setPassword(passwordEncoder.encode((String) body.get("password")));
        if (body.containsKey("roles")) user.setRoles(normalizeRoles(body.get("roles")));
        if (body.containsKey("enabled")) user.setEnabled((Boolean) body.get("enabled"));
        userMapper.updateById(user);
        return ResponseEntity.ok(Map.of("id", String.valueOf(id), "username", user.getUsername(), "roles", user.getRoles()));
    }

    @DeleteMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();
        if ("admin".equals(user.getUsername()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "不能删除 admin 用户"));
        userMapper.deleteById(id);
        log.info("Admin 删除用户: id={}, username={}", id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    // ═══════════════════════════════════════════════════════════
    // API Key 管理
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listApiKeys() {
        List<Map<String, Object>> result = apiKeyMapper.selectList(null).stream().map(k -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", String.valueOf(k.getId()));
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
    public ResponseEntity<?> createApiKey(@RequestBody Map<String, Object> body) {
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

        log.info("Admin 创建 API Key: name={}, id={}, token=mcp_sk_...", body.get("name"), result.accessKeyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", String.valueOf(result.entity().getId()),
                "accessKeyId", result.accessKeyId(),
                "accessKeySecret", result.accessKeySecret(),
                "token", result.token(),
                "name", body.get("name"),
                "serviceScope", body.getOrDefault("serviceScope", "*"),
                "message", "请保存 Token 和 AccessKey Secret，仅显示一次！Token 可直接用于 MCP Bearer 鉴权。"));
    }

    @PutMapping(value = "/api-keys/{id}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> revokeApiKey(@PathVariable String id) {
        return apiKeyService.revoke(id) ? ResponseEntity.ok(Map.of("message", "已吊销"))
                : ResponseEntity.notFound().build();
    }

    @PutMapping(value = "/api-keys/{id}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> enableApiKey(@PathVariable String id) {
        return apiKeyService.enable(id) ? ResponseEntity.ok(Map.of("message", "已启用"))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping(value = "/api-keys/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteApiKey(@PathVariable String id) {
        return apiKeyService.delete(id) ? ResponseEntity.ok(Map.of("message", "已删除"))
                : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 系统运行状态
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/system", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> systemInfo() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> info = new HashMap<>();

        info.put("javaVersion", System.getProperty("java.version"));
        info.put("jvmName", System.getProperty("java.vm.name"));
        info.put("pid", ProcessHandle.current().pid());

        long uptimeMs = System.currentTimeMillis() - getStartTime();
        info.put("uptimeMs", uptimeMs);

        info.put("heapUsed", rt.totalMemory() - rt.freeMemory());
        info.put("heapMax", rt.maxMemory());
        info.put("threadCount", Thread.activeCount());

        info.put("mcpServiceCount", serviceRegistry.getServiceCount());
        info.put("dcrEnabled", dcrEnabled);
        info.put("accessTokenTTL", accessTokenTTL);

        return ResponseEntity.ok(info);
    }

    private long getStartTime() {
        try {
            return ProcessHandle.current().info().startInstant()
                    .map(i -> i.toEpochMilli())
                    .orElse(System.currentTimeMillis());
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // MCP 服务列表（动态，从配置读取）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/services", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listServices() {
        List<Map<String, Object>> result = serviceRegistry.getServiceUrls().entrySet().stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("name", e.getKey());
            m.put("backendUrl", e.getValue());
            m.put("publicUrl", mcpServerPublicUrl + "/" + e.getKey() + "/mcp");
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/services/{serviceName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getService(@PathVariable String serviceName) {
        String backendUrl = serviceRegistry.getBackendUrl(serviceName);
        if (backendUrl == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "name", serviceName,
                "backendUrl", backendUrl,
                "publicUrl", mcpServerPublicUrl + "/" + serviceName + "/mcp"
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

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

    private static String normalizeRoles(Object roles) {
        if (roles == null) return "USER";
        String joined;
        if (roles instanceof List<?> list) {
            joined = String.join(",", list.stream().map(String::valueOf).toList());
        } else {
            joined = String.valueOf(roles);
        }
        String norm = String.join(",", java.util.Arrays.stream(joined.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList());
        return norm.isBlank() ? "USER" : norm;
    }
}
