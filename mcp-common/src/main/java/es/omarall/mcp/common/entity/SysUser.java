package es.omarall.mcp.common.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 系统用户实体 — auth-server 和 mcp-gateway 共享。
 * <p>
 * 对应表 {@code sys_user}，两个模块直连同一 MySQL 库。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId
    private Long id;
    private String username;
    private String password;
    /** 角色，逗号分隔，如 "ADMIN,USER"。驱动 @PreAuthorize 鉴权 */
    private String roles;
    private Boolean enabled;
    private Boolean accountNonExpired;
    private Boolean accountNonLocked;
    private Boolean credentialsNonExpired;
    private Instant createdAt;
    private Instant updatedAt;
}
