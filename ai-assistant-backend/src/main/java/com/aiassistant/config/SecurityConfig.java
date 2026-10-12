package com.aiassistant.config;

import com.aiassistant.common.result.Result;
import com.aiassistant.security.DbUserDetailsService;
import com.aiassistant.security.JwtAuthenticationFilter;
import com.aiassistant.security.JwtTokenService;
import com.aiassistant.security.TokenRevocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

import java.io.IOException;

/**
 * Spring Security 配置（无状态 JWT）
 * <p>
 * 过滤器链承担路由鉴权（放行清单：login/health/error、OPTIONS 预检、挂号公开端点），
 * 细粒度权限由 {@code @PreAuthorize}（@EnableMethodSecurity）承担；
 * 401/403 由 EntryPoint/DeniedHandler 输出与 {@code Result} 一致的 JSON
 *
 * @author yh
 * @date 2026-10-12
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final DbUserDetailsService dbUserDetailsService;

    /**
     * 安全过滤器链：无状态、放行公开端点、其余 /api/** 需认证
     *
     * @param http HttpSecurity 构建器
     * @return 过滤器链
     * @throws Exception 构建异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 手工构造（非 Bean），避免 Servlet 容器对 Filter Bean 的二次注册导致过滤两次
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtTokenService, tokenRevocationService, dbUserDetailsService);
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(rc -> rc.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(auth -> auth
                // CORS 预检直接放行（跨域响应头由既有 CorsConfig 的 CorsFilter 承担）
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/login", "/api/health", "/error").permitAll()
                // 挂号公开端点（患者免登录）
                .requestMatchers("/api/registration/appointments",
                        "/api/registration/appointments/**").permitAll()
                .requestMatchers("/api/registration/schedule/public/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                Result.unauthorized("未登录或登录已过期，请重新登录")))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                Result.forbidden("权限不足，禁止访问"))))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码加密器（BCrypt），供登录/改密/重置密码/播种复用
     *
     * @return BCrypt 加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器（登录走 DaoAuthenticationProvider + {@link DbUserDetailsService}）
     *
     * @param configuration 认证配置
     * @return 认证管理器
     * @throws Exception 构建异常
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 关闭 hasRole 默认的 ROLE_ 前缀：使 hasRole('admin') 命中 DB 原样角色码
     *
     * @return 权限前缀默认值
     */
    @Bean
    public static GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("");
    }

    /**
     * 输出统一 Result JSON（401/403 与业务错误体形状一致）
     *
     * @param response HTTP 响应
     * @param status HTTP 状态码
     * @param body 响应体
     * @throws IOException 写出异常
     */
    private void writeJson(HttpServletResponse response, int status, Result<Void> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
