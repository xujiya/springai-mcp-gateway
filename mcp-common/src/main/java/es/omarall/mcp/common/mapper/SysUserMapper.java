package es.omarall.mcp.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import es.omarall.mcp.common.entity.SysUser;

/**
 * 系统用户 Mapper — auth-server 和 mcp-gateway 共享。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
