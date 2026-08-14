package es.omarall.mcp.gateway;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * MCP 服务注册表 — 从 {@code ecso.mcp.services.*} 配置动态发现所有 MCP 后端服务。
 * <p>
 * 添加新 MCP 服务器只需在 application.yml 中追加配置即可，无需改代码：
 * <pre>
 * ecso:
 *   mcp:
 *     services:
 *       weather:
 *         url: http://localhost:9092/mcp
 *       climate:
 *         url: http://localhost:9093/mcp
 *       order:                          ← 新增
 *         url: http://localhost:9094/mcp
 * </pre>
 *
 * @see McpServiceRouterController
 */
@Slf4j
@Component
public class McpServiceRegistry {

    /** Service name → backend URL (e.g. "weather" → "http://localhost:9092/mcp") */
    private final Map<String, String> serviceUrls;

    public McpServiceRegistry(Environment environment) {
        this.serviceUrls = resolveServiceUrls(environment);
    }

    @PostConstruct
    void logStartup() {
        log.info("MCP Service Registry: {} services registered", serviceUrls.size());
        serviceUrls.forEach((name, url) -> log.info("  [{}] → {}", name, url));
    }

    /**
     * 获取所有已注册服务名。
     */
    public Set<String> getServiceNames() {
        return serviceUrls.keySet();
    }

    /**
     * 获取所有服务名→URL 映射（不可变）。
     */
    public Map<String, String> getServiceUrls() {
        return serviceUrls;
    }

    /**
     * 获取已注册服务数量。
     */
    public int getServiceCount() {
        return serviceUrls.size();
    }

    /**
     * 查找服务后端 URL，不存在返回 null。
     */
    public String getBackendUrl(String serviceName) {
        return serviceUrls.get(serviceName);
    }

    /**
     * 服务是否存在。
     */
    public boolean hasService(String serviceName) {
        return serviceUrls.containsKey(serviceName);
    }

    // ─────────────────────────────────────────────────────────────
    // Configuration Resolution
    // ─────────────────────────────────────────────────────────────

    private static Map<String, String> resolveServiceUrls(Environment environment) {
        Map<String, String> result = new HashMap<>();

        Binder binder = Binder.get(environment);

        var bound = binder.bind("ecso.mcp.services", Map.class);

        if (bound.isBound()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> connections = (Map<String, Object>) bound.get();
            for (String name : connections.keySet()) {
                String url = environment.getProperty("ecso.mcp.services." + name + ".url");
                if (url != null && !url.isBlank()) {
                    result.put(name, url);
                }
            }
        }

        return Collections.unmodifiableMap(result);
    }
}
