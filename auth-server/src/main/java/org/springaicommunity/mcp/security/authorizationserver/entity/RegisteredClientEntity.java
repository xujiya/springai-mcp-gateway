package org.springaicommunity.mcp.security.authorizationserver.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("oauth2_registered_client")
public class RegisteredClientEntity {

    @TableId
    private String id;
    private String clientId;
    private Instant clientIdIssuedAt;
    private String clientSecret;
    private Instant clientSecretExpiresAt;
    private String clientName;
    private String clientAuthenticationMethods;
    private String authorizationGrantTypes;
    private String redirectUris;
    private String scopes;
    private String clientSettings;
    private String tokenSettings;
    /** 注册来源: DCR (动态注册) / PRE-REGISTERED (data.sql 预置) / ADMIN (管理后台创建) */
    private String registrationSource;
}
