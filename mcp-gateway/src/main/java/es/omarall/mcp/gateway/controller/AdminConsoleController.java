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
import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.gateway.entity.OAuth2Client;
import es.omarall.mcp.gateway.mapper.SysUserMapper;
import es.omarall.mcp.gateway.mapper.ApiKeyMapper;
import es.omarall.mcp.gateway.mapper.OAuth2ClientMapper;
import es.omarall.mcp.gateway.service.ApiKeyService;

/**
 * 管理后台 API（mcp-gateway）：登录 + 用户管理 + API Key 管理 + 运行状态。
 * <p>
 * <b>架构原则</b>：网关层是唯一认证边界，过了网关的内部流量不需 token 认证。
 * 用户管理归 mcp-gateway（与 API Key 同级），auth-server 只负责 OAuth2 协议端点。
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
    private final ApiKeyMapper apiKeyMapper;
    private final OAuth2ClientMapper clientMapper;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder passwordEncoder;
    private final String adminTokenHash;
    private final String adminTokenPlaintext;
    private final int mcpServiceCount;
    private final boolean dcrEnabled;
    private final String accessTokenTTL;

    public AdminConsoleController(
            SysUserMapper userMapper,
            ApiKeyMapper apiKeyMapper,
            OAuth2ClientMapper clientMapper,
            ApiKeyService apiKeyService,
            PasswordEncoder passwordEncoder,
            @Value("${ecso.mcp.api-key.admin-token-hash:}") String adminTokenHash,
            @Value("${ecso.mcp.api-key.admin-token:}") String adminTokenPlaintext,
            @Value("${ecso.mcp.services.weather.url:}") String weatherUrl,
            @Value("${ecso.mcp.services.climate.url:}") String climateUrl,
            @Value("${ecso.auth.dcr.enabled:true}") boolean dcrEnabled,
            @Value("${ecso.auth.dcr.access-token-time-to-live:24h}") String accessTokenTTL) {
        this.userMapper = userMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.clientMapper = clientMapper;
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
        if (user == null) return unauthorized("用户名或密码错误");

        // DelegatingPasswordEncoder 需要 {bcrypt} 前缀来分派
        if (!passwordEncoder.matches(password, user.getPassword())) return unauthorized("用户名或密码错误");
        if (!Boolean.TRUE.equals(user.getEnabled())) return forbidden("账号已禁用");

        if (adminTokenPlaintext.isBlank()) return serverError("未配置 admin token");

        log.info("管理后台登录: username={}", username);
        return ResponseEntity.ok(Map.of(
                "username", username,
                "adminToken", adminTokenPlaintext,
                "message", "登录成功"
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // OAuth2 客户端列表（oauth2_registered_client，只读展示）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listClients(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
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
    public ResponseEntity<?> listUsers(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
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
    public ResponseEntity<?> updateUser(@RequestHeader("Authorization") String auth,
                                        @PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
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
    public ResponseEntity<?> deleteUser(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");
        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();
        if ("admin".equals(user.getUsername()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "不能删除 admin 用户"));
        userMapper.deleteById(id);
        log.info("Admin 删除用户: id={}, username={}", id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    // ═══════════════════════════════════════════════════════════
    // API Key 管理（mcp-gateway 私域凭证，管理权与校验权同源）
    // ═══════════════════════════════════════════════════════════

    @GetMapping(value = "/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listApiKeys(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) return unauthorized("invalid_admin_token");

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

        info.put("javaVersion", System.getProperty("java.version"));
        info.put("jvmName", System.getProperty("java.vm.name"));
        info.put("pid", ProcessHandle.current().pid());

        long uptimeMs = System.currentTimeMillis() - getStartTime();
        info.put("uptimeMs", uptimeMs);

        info.put("heapUsed", rt.totalMemory() - rt.freeMemory());
        info.put("heapMax", rt.maxMemory());
        info.put("threadCount", Thread.activeCount());

        info.put("mcpServiceCount", mcpServiceCount);
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
