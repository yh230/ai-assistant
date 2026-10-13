package com.common.jwt;

import com.common.constant.SecurityConstants;
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
 * token/sub 存放 userId，identity claim 标识身份（staff/patient），
 * jti 供登出黑名单使用，iat/exp 供踢下线与过期判定
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
     * 为指定用户签发令牌（员工身份）
     *
     * @param userId 用户 ID（sys_user.id）
     * @return JWT 字符串
     */
    public String issue(Long userId) {
        return issue(userId, SecurityConstants.IDENTITY_STAFF);
    }

    /**
     * 为指定用户签发令牌（携带身份标识）
     *
     * @param userId 用户 ID（sys_user.id 或 patient_user.id）
     * @param identity 身份类型（staff/patient）
     * @return JWT 字符串
     */
    public String issue(Long userId, String identity) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("identity", identity)
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
        String identity = claims.get("identity", String.class);
        return new JwtPayload(Long.valueOf(claims.getSubject()),
                identity == null ? SecurityConstants.IDENTITY_STAFF : identity,
                claims.getId(), claims.getIssuedAt().getTime(), claims.getExpiration().getTime());
    }

    /** JWT 载荷（userId / identity / jti / 签发与过期时间） */
    public record JwtPayload(Long userId, String identity, String jti, long issuedAtMillis, long expiresAtMillis) {
    }
}
