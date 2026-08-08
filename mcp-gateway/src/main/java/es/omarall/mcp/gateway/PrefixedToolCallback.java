package es.omarall.mcp.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * ToolCallback wrapper that applies a prefix to tool names to avoid collisions.
 * <p>
 * In ALIAS mode: {alias}{delimiter}{toolName} (e.g. "weather_getAlerts")
 * In STATIC mode: {staticPrefix}{delimiter}{toolName} (e.g. "gw_getAlerts")
 * In NONE mode: toolName unchanged (not wrapped, see GatewayProvidersConfig)
 */
@Slf4j
public class PrefixedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final McpGatewayProperties props;
    private final String alias;

    public PrefixedToolCallback(ToolCallback delegate, McpGatewayProperties props, String alias) {
        this.delegate = delegate;
        this.props = props;
        this.alias = alias;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition td = delegate.getToolDefinition();
        String mappedName = mapName(td.name());
        log.trace("Mapping tool name '{}' to '{}'", td.name(), mappedName);
        return new PrefixedToolDefinition(
                mappedName,
                td.description(),
                td.inputSchema()
        );
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        log.debug("Calling tool '{}' with input: {}", getToolDefinition().name(), toolInput);
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return call(toolInput);
    }

    /**
     * Maps the tool name according to the configured prefix mode.
     */
    private String mapName(String toolName) {
        return switch (props.getPrefixMode()) {
            case NONE -> toolName;
            case STATIC -> props.getStaticPrefix() + props.getDelimiter() + toolName;
            case ALIAS -> alias + props.getDelimiter() + toolName;
        };
    }
}
