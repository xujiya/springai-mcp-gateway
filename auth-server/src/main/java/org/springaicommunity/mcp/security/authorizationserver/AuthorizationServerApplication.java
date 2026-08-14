package org.springaicommunity.mcp.security.authorizationserver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Authorization Server — OAuth2 授权服务器 + DCR 端点。
 * <p>
 * 纯协议层：OAuth2 authorize/token/introspect + RFC 7591 DCR。
 * 用户/客户端管理由 mcp-gateway admin API 负责。
 */
@SpringBootApplication
@MapperScan({"org.springaicommunity.mcp.security.authorizationserver.mapper", "es.omarall.mcp.common.mapper"})
public class AuthorizationServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthorizationServerApplication.class, args);
	}

}
