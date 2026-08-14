package es.omarall.mcp.common.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * OAuth2 客户端（oauth2_registered_client 表，只读列表展示用）。
 * 完整的 RegisteredClient 操作仍由 auth-server 的 Spring Authorization Server 负责。
 */
@Data
@TableName("oauth2_registered_client")
public class OAuth2Client {

    @TableId
    private String id;
    private String clientId;
    private Instant clientIdIssuedAt;
    private String clientName;
    private String clientAuthenticationMethods;
    private String authorizationGrantTypes;
    private String redirectUris;
    private String scopes;
    private String registrationSource;
}
