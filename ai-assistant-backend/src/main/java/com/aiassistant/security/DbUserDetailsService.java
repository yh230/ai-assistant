package com.aiassistant.security;

import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysMenuMapper;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库用户认证信息源
 * <p>
 * 角色码与权限标识从 DB **实时**加载（权限回收即时生效）；角色不加 ROLE_ 前缀，
 * 配合 SecurityConfig 的 {@code GrantedAuthorityDefaults("")} 使 {@code hasRole('admin')} 命中原样角色码
 *
 * @author yh
 * @date 2026-10-12
 */
@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;

    /**
     * 按用户名加载认证主体（登录校验用）
     *
     * @param username 登录账号
     * @return 认证主体
     * @throws UsernameNotFoundException 账号不存在
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("账号不存在");
        }
        return new LoginUser(user.getId(), user.getUsername(), user.getPassword(),
                user.getStatus() != null && user.getStatus() == 1, loadAuthorities(user.getId()));
    }

    /**
     * 按用户 ID 加载认证主体（JWT 过滤器每请求调用；实时加载权限/角色）
     *
     * @param userId 用户 ID
     * @return 认证主体；用户不存在或被禁用时返回 null（视为未认证）
     */
    public LoginUser loadByUserId(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        return new LoginUser(user.getId(), user.getUsername(), null, true, loadAuthorities(user.getId()));
    }

    /**
     * 组装角色码 + 权限标识（去重，保持 DB 实时）
     *
     * @param userId 用户 ID
     * @return authorities 列表
     */
    private List<GrantedAuthority> loadAuthorities(Long userId) {
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        List<String> perms = menuMapper.selectPermsByUserId(userId);
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            roles.stream().filter(StringUtils::hasText).distinct()
                    .forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        }
        if (perms != null) {
            perms.stream().filter(StringUtils::hasText).distinct()
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        return authorities;
    }

    /** 轻量认证主体（userId 为业务主键；密码仅登录路径携带） */
    public record LoginUser(Long userId, String username, String password, boolean enabled,
                            List<GrantedAuthority> authorities) implements UserDetails {

        @Override
        public List<GrantedAuthority> getAuthorities() {
            return authorities;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
