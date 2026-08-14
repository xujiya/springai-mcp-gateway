package org.springaicommunity.mcp.security.authorizationserver.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Consent-first filter: intercepts GET /oauth2/authorize BEFORE Spring's
 * {@code OAuth2AuthorizationEndpointFilter}. If the user has not yet viewed the
 * consent page for this client, redirect to the Vue consent page carrying the
 * original authorize URL as {@code return_to}. After consent is recorded in the
 * session (by {@link ConsentController}), the request flows through to Spring's
 * normal authorize handling (login if unauthenticated, then issue code).
 *
 * <p>Mirrors the reference project's flow: authorize → consent page → "同意并登录"
 * → login → code. Consent here is a UX pre-authorization step (displaying which
 * client/scope is requesting access), not the OAuth2 protocol consent decision
 * (which remains disabled via {@code requireAuthorizationConsent(false)}).
 */
public class ConsentFilter extends OncePerRequestFilter {

    public static final String CONSENT_ATTR_PREFIX = "consent:";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String clientId = null;
        if ("GET".equalsIgnoreCase(request.getMethod())
                && "/oauth2/authorize".equals(request.getRequestURI())) {
            clientId = request.getParameter("client_id");
            if (clientId != null && !clientId.isBlank()) {
                HttpSession session = request.getSession(false);
                boolean consented = session != null
                        && "true".equals(session.getAttribute(CONSENT_ATTR_PREFIX + clientId));
                if (!consented) {
                    String uri = request.getRequestURI();
                    String qs = request.getQueryString();
                    String returnTo = qs != null ? uri + "?" + qs : uri;
                    String target = "/vue-consent?return_to="
                            + URLEncoder.encode(returnTo, StandardCharsets.UTF_8)
                            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
                    response.sendRedirect(target);
                    return;
                }
            }
        }
        // 已 consent（或非 authorize GET）→ chain 继续。
        // 用 wrapper 拦截 Spring Security 的 sendRedirect，给 /vue-login 带上 authorize 的完整 query
        // （方案 B：consent→login 传递参数，参考 3013 consent 页用 state 传 authorizationCode 的思路）
        final String authzQuery = request.getQueryString();
        if (clientId != null && !clientId.isBlank()) {
            HttpServletResponse wrapped = new HttpServletResponseWrapper(response) {
                @Override
                public void sendRedirect(String location) throws java.io.IOException {
                    if (location != null && location.contains("/vue-login")
                            && authzQuery != null && !authzQuery.isBlank()) {
                        String sep = location.contains("?") ? "&" : "?";
                        location = location + sep + authzQuery;
                    }
                    super.sendRedirect(location);
                }
            };
            filterChain.doFilter(request, wrapped);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
