package es.omarall.mcp.apigateway;

import java.util.ArrayList;
import java.util.List;

import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Rewrites {@code Location} headers on 3xx redirect responses so that internal
 * backend URLs are replaced with public gateway URLs — all handled at the
 * gateway layer, no nginx proxy_redirect needed.
 *
 * <p>Usage in YAML:
 * <pre>
 * filters:
 *   - RedirectLocationRewrite=
 *       http://localhost:9090/vue-login → http://localhost:8080/api-gateway/ecso/vue,
 *       http://localhost:9090/           → http://localhost:8080/api-gateway/ecso/auth/
 * </pre>
 */
@Component
public class RedirectLocationRewriteGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RedirectLocationRewriteGatewayFilterFactory.Config> {

    public RedirectLocationRewriteGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        List<Mapping> mappings = parseMappings(config.getMappings());

        return (exchange, chain) -> {
            ServerHttpResponse original = exchange.getResponse();

            ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(original) {

                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    rewriteLocationIfRedirect(getHeaders(), getStatusCode(), mappings);
                    return super.writeWith(body);
                }

                @Override
                public Mono<Void> writeAndFlushWith(
                        Publisher<? extends Publisher<? extends DataBuffer>> bodyWithFlush) {
                    rewriteLocationIfRedirect(getHeaders(), getStatusCode(), mappings);
                    return super.writeAndFlushWith(bodyWithFlush);
                }

                @Override
                public Mono<Void> setComplete() {
                    rewriteLocationIfRedirect(getHeaders(), getStatusCode(), mappings);
                    return super.setComplete();
                }


            };

            ServerWebExchange mutated = exchange.mutate().response(decorated).build();
            return chain.filter(mutated);
        };
    }

    private static void rewriteLocationIfRedirect(
            HttpHeaders headers, HttpStatusCode statusCode, List<Mapping> mappings) {
        if (statusCode == null || !statusCode.is3xxRedirection()) {
            return;
        }
        String location = headers.getFirst(HttpHeaders.LOCATION);
        if (location == null || location.isEmpty()) {
            return;
        }
        for (Mapping m : mappings) {
            if (location.startsWith(m.internalPrefix())) {
                String rewritten = m.publicPrefix() + location.substring(m.internalPrefix().length());
                headers.set(HttpHeaders.LOCATION, rewritten);
                return;
            }
        }
    }

    private List<Mapping> parseMappings(String raw) {
        List<Mapping> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;
        for (String part : raw.split(",")) {
            part = part.trim();
            int arrow = part.indexOf("\u2192"); // →
            if (arrow < 0) arrow = part.indexOf("->");
            if (arrow < 0) continue;
            String internalPrefix = part.substring(0, arrow).trim();
            int arrowLen = part.charAt(arrow) == '\u2192' ? 1 : 2;
            String publicPrefix  = part.substring(arrow + arrowLen).trim();
            list.add(new Mapping(internalPrefix, publicPrefix));
        }
        return list;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    public static class Config {
        /** Comma-separated mappings: "internal1 → public1, internal2 → public2" */
        private String mappings;
        public String getMappings() { return mappings; }
        public void setMappings(String mappings) { this.mappings = mappings; }
    }

    private record Mapping(String internalPrefix, String publicPrefix) {}
}
