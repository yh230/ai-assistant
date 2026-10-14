package com.common.security;

import com.api.client.AuthClient;
import com.common.auth.UserSnapshotStore;
import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
 * Spring Security 自动配置（无状态 JWT + 内部令牌）
 * <p>
 * 过滤器链承担路由鉴权（放行清单：login/health/error、患者注册/登录、OPTIONS 预检、挂号公开端点、内部接口），
 * 细粒度权限由 {@code @PreAuthorize}（@EnableMethodSecurity）承担；
 * 401/403 由 EntryPoint/DeniedHandler 输出与 {@code Result} 一致的 JSON；
 * 登录校验已下沉 system-service，本模块不注册 AuthenticationManager
 *
 * @author yh
 * @date 2026-10-12
 */
@AutoConfiguration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SecurityAutoConfiguration {

    private final ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final UserSnapshotStore userSnapshotStore;
    private final AuthClient authClient;

    /** 服务间内部调用令牌（与 api 模块 InternalTokenInterceptor 附加的请求头对应） */
    @Value("${security.internal.token:}")
    private String internalToken;

    /**
     * 安全过滤器链：无状态、放行公开端点与内部接口、其余 /api/** 需认证
     *
     * @param http HttpSecurity 构建器
     * @return 过滤器链
     * @throws Exception 构建异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 手工构造（非 Bean），避免 Servlet 容器对 Filter Bean 的二次注册导致过滤两次
        InternalAuthFilter internalAuthFilter = new InternalAuthFilter(internalToken, objectMapper);
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtTokenService, tokenRevocationService,
                        userSnapshotStore, authClient, objectMapper);
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(rc -> rc.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(auth -> auth
                // CORS 预检直接放行（跨域响应头由既有 CorsConfig 的 CorsFilter 承担）
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/login", "/api/health", "/error").permitAll()
                // 令牌续期/吊销（refresh token 公开端点）
                .requestMatchers("/api/refresh", "/api/refresh/revoke").permitAll()
                // 患者公开端点（注册/登录免登录）
                .requestMatchers("/api/patient/login", "/api/patient/register").permitAll()
                // 医院公开列表（患者注册选院）
                .requestMatchers("/api/hospital/public/**").permitAll()
                // 挂号公开端点（患者免登录）
                .requestMatchers("/api/registration/appointments",
                        "/api/registration/appointments/**").permitAll()
                .requestMatchers("/api/registration/schedule/public/**").permitAll()
                // 内部接口（安全由 InternalAuthFilter 校验内部令牌承担）
                .requestMatchers("/inner/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                Result.unauthorized("未登录或登录已过期，请重新登录")))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                Result.forbidden("权限不足，禁止访问"))))
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
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
     * 关闭 hasRole 默认的 ROLE_ 前缀：使 hasRole('admin') 命中快照原样角色码
     *
     * @return 权限前缀默认值
     */
    @Bean
    public static GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("");
    }

    /**
     * 全局异常处理器（原 com.common.exception 迁入，作为 bean 注册生效）
     *
     * @return 全局异常处理器
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
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
