package org.springaicommunity.mcp.security.authorizationserver.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import es.omarall.mcp.common.entity.SysUser;
import es.omarall.mcp.common.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class MybatisUserDetailsService implements UserDetailsService {

    private final SysUserMapper mapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = mapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        // 从 sys_user.roles 字段解析角色，驱动 @PreAuthorize 鉴权（如 ROLE_ADMIN）
        String[] roles = parseRoles(user.getRoles());

        return User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .disabled(!user.getEnabled())
                .accountExpired(!user.getAccountNonExpired())
                .accountLocked(!user.getAccountNonLocked())
                .credentialsExpired(!user.getCredentialsNonExpired())
                .roles(roles)
                .build();
    }

    /** 解析逗号分隔的 roles 字段；为空时默认 USER（向后兼容） */
    private static String[] parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return new String[] {"USER"};
        }
        String[] arr = Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return arr.length == 0 ? new String[] {"USER"} : arr;
    }
}
