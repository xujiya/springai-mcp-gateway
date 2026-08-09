package es.omarall.mcp.gateway.controller;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.gateway.service.ApiKeyService;

/**
 * Admin API for API Key CRUD.
 * <p>
 * Protected by a configurable admin Bearer token ({@code ecso.mcp.api-key.admin-token}).
 * This is a simple auth mechanism suitable for internal admin access,
 * similar to Kubernetes service account tokens.
 * <p>
 * Usage:
 * <pre>
 * # Create a new API key
 * curl -X POST http://localhost:8082/admin/api-keys \
 *   -H "Authorization: Bearer mcp-admin-2025" \
 *   -H "Content-Type: application/json" \
 *   -d '{"name": "ci-cd-key", "serviceScope": "weather", "description": "CI/CD pipeline"}'
 *
 * # List all API keys
 * curl http://localhost:8082/admin/api-keys \
 *   -H "Authorization: Bearer mcp-admin-2025"
 *
 * # Revoke an API key
 * curl -X PUT http://localhost:8082/admin/api-keys/{id}/revoke \
 *   -H "Authorization: Bearer mcp-admin-2025"
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;
    private final String adminToken;

    public ApiKeyAdminController(
            ApiKeyService apiKeyService,
            @Value("${ecso.mcp.api-key.admin-token:mcp-admin-2025}") String adminToken) {
        this.apiKeyService = apiKeyService;
        this.adminToken = adminToken;
    }

    // ─────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody CreateRequest req) {

        if (!authenticateAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_admin_token"));
        }

        Instant expiresAt = null;
        if (req.expiresAt() != null && !req.expiresAt().isBlank()) {
            try {
                expiresAt = Instant.parse(req.expiresAt());
            } catch (Exception e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "invalid_expires_at", "hint", "Use ISO-8601 format, e.g. 2026-01-01T00:00:00Z"));
            }
        }

        String rawKey = apiKeyService.create(
                req.name(),
                req.serviceScope(),
                req.description(),
                req.createdBy(),
                expiresAt);

        log.info("Admin created API key: name={}, scope={}", req.name(), req.serviceScope());

        // Return the plaintext key — this is the ONLY time it's shown!
        Map<String, Object> response = new HashMap<>();
        response.put("message", "API key created. Save the key — it will NOT be shown again!");
        response.put("apiKey", rawKey);
        response.put("name", req.name());
        response.put("serviceScope", req.serviceScope() != null ? req.serviceScope() : "*");
        response.put("expiresAt", expiresAt != null ? expiresAt.toString() : "never");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─────────────────────────────────────────────────────────────
    // List
    // ─────────────────────────────────────────────────────────────

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@RequestHeader("Authorization") String authHeader) {
        if (!authenticateAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_admin_token"));
        }

        List<ApiKey> keys = apiKeyService.listAll();
        // Never return the hash in the response
        List<Map<String, Object>> result = keys.stream().map(k -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", k.getId());
            map.put("name", k.getName());
            map.put("apiKeyPrefix", k.getApiKeyPrefix());
            map.put("serviceScope", k.getServiceScope());
            map.put("description", k.getDescription());
            map.put("createdBy", k.getCreatedBy());
            map.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : null);
            map.put("expiresAt", k.getExpiresAt() != null ? k.getExpiresAt().toString() : "never");
            map.put("enabled", k.getEnabled());
            map.put("lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : null);
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────
    // Revoke
    // ─────────────────────────────────────────────────────────────

    @PutMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String id) {
        if (!authenticateAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_admin_token"));
        }

        boolean success = apiKeyService.revoke(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "API key revoked", "id", id));
    }

    // ─────────────────────────────────────────────────────────────
    // Enable
    // ─────────────────────────────────────────────────────────────

    @PutMapping("/{id}/enable")
    public ResponseEntity<?> enable(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String id) {
        if (!authenticateAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_admin_token"));
        }

        boolean success = apiKeyService.enable(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "API key enabled", "id", id));
    }

    // ─────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String id) {
        if (!authenticateAdmin(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_admin_token"));
        }

        boolean success = apiKeyService.delete(id);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "API key deleted", "id", id));
    }

    // ─────────────────────────────────────────────────────────────
    // Auth Helper
    // ─────────────────────────────────────────────────────────────

    private boolean authenticateAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        return adminToken.equals(token);
    }

    // ─────────────────────────────────────────────────────────────
    // Request DTOs
    // ─────────────────────────────────────────────────────────────

    public record CreateRequest(
            String name,
            String serviceScope,
            String description,
            String createdBy,
            String expiresAt  // ISO-8601 or null for never
    ) {}
}
