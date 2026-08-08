package es.omarall.mcp.apigateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Rewrites auth-server internal URLs in JSON response bodies to public gateway URLs.
 * Usage: RewriteAuthUrls=http://localhost:9090,http://localhost:8080/api-gateway/ecso/auth
 */
@Component
public class RewriteAuthUrlsGatewayFilterFactory extends AbstractGatewayFilterFactory<RewriteAuthUrlsGatewayFilterFactory.Config> {

    public RewriteAuthUrlsGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        String internalUrl = config.getInternalUrl();
        String publicUrl = config.getPublicUrl();

        return (exchange, chain) -> {
            ServerHttpResponse originalResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();

            ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(originalResponse) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    String ct = getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                    if (ct != null && (ct.contains(MediaType.APPLICATION_JSON_VALUE)
                            || ct.contains("application/jwk-set+json"))) {
                        return super.writeWith(Flux.from(body).map(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            String content = new String(bytes, StandardCharsets.UTF_8);
                            String rewritten = content.replace(internalUrl, publicUrl);
                            return bufferFactory.wrap(rewritten.getBytes(StandardCharsets.UTF_8));
                        }));
                    }
                    return super.writeWith(body);
                }
            };

            ServerWebExchange mutatedExchange = exchange.mutate().response(decorated).build();
            return chain.filter(mutatedExchange);
        };
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    public static class Config {
        private String internalUrl;
        private String publicUrl;

        public String getInternalUrl() { return internalUrl; }
        public void setInternalUrl(String internalUrl) { this.internalUrl = internalUrl; }
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    }
}
