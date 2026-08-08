package es.omarall.mcp.gateway;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Boot configuration for registering ToolCallback providers for the gateway.
 * <p>
 * In ALIAS mode, tool names are prefixed with the MCP client connection name
 * (e.g. "weather_getAlerts", "climate_getStormWarnings") to avoid collisions
 * when multiple backends expose tools with the same original name.
 */
@Slf4j
@SpringBootConfiguration
public class GatewayProvidersConfig {

    /**
     * Registers a ToolCallbackProvider bean that aggregates both synchronous and asynchronous MCP clients.
     * @param mcpClients list of synchronous MCP clients
     * @param mcpAsyncClients list of asynchronous MCP clients
     * @param props gateway properties
     * @return ToolCallbackProvider instance
     */
    @Bean
    @Primary
    public ToolCallbackProvider tcProvider(
            List<McpSyncClient> mcpClients,
            List<McpAsyncClient> mcpAsyncClients,
            McpGatewayProperties props) {

        log.info("Registering ToolCallbackProvider with {} sync and {} async clients", mcpClients.size(), mcpAsyncClients.size());

        final List<ToolCallback> tcs = new ArrayList<>(mcpClients.stream()
                .flatMap(mcpClient -> {
                    // Use the client connection name as alias (e.g. "weather", "climate")
                    String alias = mcpClient.getClientInfo().name();
                    return mcpClient.listTools()
                            .tools()
                            .stream()
                            .map(tool -> new SyncMcpToolCallback(mcpClient, tool))
                            .map(tc -> wrap(tc, alias, props));
                })
                .toList());

        ToolCallbackProvider asyncToolCallbackProvider = new AsyncMcpToolCallbackProvider(mcpAsyncClients);
        Arrays.stream(asyncToolCallbackProvider.getToolCallbacks()).forEach(tc -> {
            // For async clients, use client info name as alias
            String alias = mcpAsyncClients.isEmpty() ? "async" :
                    mcpAsyncClients.get(0).getClientInfo().name();
            ToolCallback t = wrap(tc, alias, props);
            tcs.add(t);
        });

        log.debug("Total ToolCallbacks registered: {}", tcs.size());
        return ToolCallbackProvider.from(tcs);
    }

    private ToolCallback wrap(ToolCallback tc, String alias, McpGatewayProperties props) {
        if (props.getPrefixMode() == McpGatewayProperties.PrefixMode.NONE) {
            return tc;
        }
        return new PrefixedToolCallback(tc, props, alias);
    }
}
