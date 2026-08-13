package org.springaicommunity.mcp.security.authorizationserver.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springaicommunity.mcp.security.authorizationserver.entity.SysUser;
import org.springaicommunity.mcp.security.authorizationserver.mapper.SysUserMapper;

/**
 * 用户管理 API（自定义用户 sys_user 的 CRUD）。
 * <p>
 * 所有端点要求登录用户具备 {@code ROLE_ADMIN}（@PreAuthorize）。
 * 角色来源：{@code sys_user.roles} 字段（逗号分隔），由 {@code MybatisUserDetailsService} 解析。
 * <p>
 * 密码用 {@link PasswordEncoder#encode} 编码 —— DelegatingPasswordEncoder 产出带
 * {@code {bcrypt}} 前缀的单前缀哈希，<b>禁止</b>手动拼接前缀（否则双前缀导致校验失败）。
 */
@Slf4j
@RestController
@RequestMapping("/oauth2/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> list() {
        List<Map<String, Object>> result = userMapper.selectList(null).stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("roles", u.getRoles());
            m.put("enabled", u.getEnabled());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        if (username == null || password == null) {
            return badRequest("缺少 username 或 password");
        }

        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "用户名已存在"));
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        // DelegatingPasswordEncoder.encode() 已带 {bcrypt} 前缀，禁止手动拼接
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(normalizeRoles(body.get("roles")));
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        userMapper.insert(user);

        log.info("Admin 创建用户: username={}, roles={}", username, user.getRoles());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", user.getId(), "username", username, "roles", user.getRoles()));
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.containsKey("username")) user.setUsername((String) body.get("username"));
        if (body.containsKey("password")) user.setPassword(passwordEncoder.encode((String) body.get("password")));
        if (body.containsKey("roles")) user.setRoles(normalizeRoles(body.get("roles")));
        if (body.containsKey("enabled")) user.setEnabled((Boolean) body.get("enabled"));
        userMapper.updateById(user);
        return ResponseEntity.ok(Map.of("id", id, "username", user.getUsername(), "roles", user.getRoles()));
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if ("admin".equals(user.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "不能删除 admin 用户"));
        }
        userMapper.deleteById(id);
        log.info("Admin 删除用户: id={}, username={}", id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    /** 把 roles 入参（字符串 "ADMIN,USER" 或数组）归一为逗号分隔大写字符串 */
    private static String normalizeRoles(Object roles) {
        if (roles == null) return "USER";
        String joined;
        if (roles instanceof List<?> list) {
            joined = String.join(",", list.stream().map(String::valueOf).toList());
        } else {
            joined = String.valueOf(roles);
        }
        String norm = String.join(",", java.util.Arrays.stream(joined.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList());
        return norm.isBlank() ? "USER" : norm;
    }

    private ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
