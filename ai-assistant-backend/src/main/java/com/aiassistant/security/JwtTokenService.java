package com.aiassistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 令牌服务：签发与解析（HS256 固定算法）
 * <p>
 * 变更点（Sa-Token→Spring Security 迁移）：替代原 Sa-Token 的 uuid token 生成与解析
 * token/sub 存放 userId，jti 供登出黑名单使用，iat/exp 供踢下线与过期判定
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    /**
     * 构造令牌服务
     *
     * @param secret JWT 签名密钥（HS256 要求至少 32 字节）
     * @param expirationSeconds 令牌有效期（秒）
     */
    public JwtTokenService(@Value("${security.jwt.secret}") String secret,
                           @Value("${security.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 为指定用户签发令牌
     *
     * @param userId 用户 ID（sys_user.id）
     * @return JWT 字符串
     */
    public String issue(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验令牌（签名/过期）
     *
     * @param token JWT 字符串
     * @return 令牌载荷
     * @throws io.jsonwebtoken.JwtException 无效或过期时抛出
     */
    public JwtPayload parse(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();
        return new JwtPayload(Long.valueOf(claims.getSubject()), claims.getId(),
                claims.getIssuedAt().getTime(), claims.getExpiration().getTime());
    }

    /** JWT 载荷（userId / jti / 签发与过期时间） */
    public record JwtPayload(Long userId, String jti, long issuedAtMillis, long expiresAtMillis) {
    }
}
