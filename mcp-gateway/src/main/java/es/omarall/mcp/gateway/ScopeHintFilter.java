package es.omarall.mcp.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * 增强 MCP Gateway 的 OAuth2 响应以符合 MCP 规范：
 * <p>
 * 1. 401 WWW-Authenticate header 追加 scope 参数（Scope Selection Strategy, RFC 6750 §3）
 * 2. Protected Resource Metadata JSON 追加 scopes_supported 字段
 */
public class ScopeHintFilter implements Filter {

    private static final String REQUIRED_SCOPES = "mcp:read mcp:write";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String uri = req.getRequestURI();

        // 对 Protected Resource Metadata 请求，用 wrapping response 拦截 body
        if (uri.contains("oauth-protected-resource")) {
            var wrapper = new BufferingResponseWrapper((HttpServletResponse) response);
            chain.doFilter(request, wrapper);

            if (wrapper.getStatus() == 200 && wrapper.getContentType() != null
                    && wrapper.getContentType().contains("application/json")) {
                String body = wrapper.getBody();
                try {
                    JsonNode node = mapper.readTree(body);
                    if (!node.has("scopes_supported")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("scopes_supported",
                                mapper.createArrayNode().add("mcp:read").add("mcp:write"));
                        String enhanced = mapper.writeValueAsString(node);
                        response.setContentLength(enhanced.getBytes(StandardCharsets.UTF_8).length);
                        response.getWriter().write(enhanced);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            // 不需要增强，写回原始 body
            if (!response.isCommitted()) {
                response.getWriter().write(wrapper.getBody());
            }
            return;
        }

        // 普通请求：正常 chain
        chain.doFilter(request, response);

        // 增强 401 WWW-Authenticate header
        if (response instanceof HttpServletResponse httpResponse && httpResponse.getStatus() == 401) {
            String wwwAuth = httpResponse.getHeader("WWW-Authenticate");
            if (wwwAuth != null && wwwAuth.startsWith("Bearer") && !wwwAuth.contains("scope=")) {
                String enhanced = wwwAuth + ", scope=\"" + REQUIRED_SCOPES + "\"";
                httpResponse.setHeader("WWW-Authenticate", enhanced);
            }
        }
    }

    /** Wraps response to buffer the output */
    private static class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override public void write(int b) { buffer.write(b); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener l) {}
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(buffer);
            }
            return writer;
        }

        String getBody() {
            if (writer != null) writer.flush();
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
