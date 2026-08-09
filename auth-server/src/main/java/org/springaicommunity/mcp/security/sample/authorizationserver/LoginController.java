package org.springaicommunity.mcp.security.sample.authorizationserver;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
class LoginController {

    @GetMapping(value = "/vue-login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String vueLogin() {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"zh\"><head><meta charset=\"UTF-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"/>" +
            "<title>MCP Auth Server - Login</title>" +
            "<script type=\"module\" crossorigin src=\"/api-gateway/ecso/vue/assets/index-DBNAin-e.js\"></script>" +
            "<link rel=\"stylesheet\" crossorigin href=\"/api-gateway/ecso/vue/assets/index-D4U55mnN.css\">" +
            "</head><body><div id=\"app\"></div></body></html>";
    }

    @GetMapping("/")
    @ResponseBody
    public java.util.Map<String, String> home(@AuthenticationPrincipal User user) {
        return java.util.Map.of("username", user.getUsername(), "authorities", user.getAuthorities().toString());
    }
}
