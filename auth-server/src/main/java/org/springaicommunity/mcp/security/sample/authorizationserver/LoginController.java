package org.springaicommunity.mcp.security.sample.authorizationserver;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后端只暴露 JSON API，不渲染任何前端 HTML（前后端完全分离）。
 *
 * <p>{@code /vue-login} 不再有 handler：Spring Security 的 {@code loginPage("/vue-login")}
 * 在未认证时 302 到 {@code /vue-login}，由 API Gateway 的 RewriteResponseHeader 把
 * {@code 9090/vue-login} 重写为公网 Vue 前端登录页 {@code 8080/api-gateway/ecso/vue/}。
 * 前端页面（登录/consent）全部由前端工程（Vite/serve-dist :9091）独立 serve。
 */
@RestController
class LoginController {

    /** 已登录主页：返回当前用户信息（JSON API，非页面）。 */
    @GetMapping("/")
    public Map<String, String> home(@AuthenticationPrincipal User user) {
        return Map.of(
                "username", user.getUsername(),
                "authorities", user.getAuthorities().toString());
    }
}
