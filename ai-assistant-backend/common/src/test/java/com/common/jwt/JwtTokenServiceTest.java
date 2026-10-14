package com.common.jwt;

import com.common.constant.SecurityConstants;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JwtTokenService 单元测试：双身份签发与旧令牌兼容解析
 *
 * @author yh
 * @date 2026-10-13
 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789";

    private final JwtTokenService service = new JwtTokenService(SECRET, 3600);

    @Test
    void issueWithDefaultIdentityIsStaff() {
        String token = service.issue(1L);
        assertEquals(SecurityConstants.IDENTITY_STAFF, service.parse(token).identity());
    }

    @Test
    void issuePatientTokenCarriesIdentity() {
        String token = service.issue(7L, SecurityConstants.IDENTITY_PATIENT);
        JwtTokenService.JwtPayload payload = service.parse(token);
        assertEquals(7L, payload.userId());
        assertEquals(SecurityConstants.IDENTITY_PATIENT, payload.identity());
    }

    @Test
    void parseLegacyTokenWithoutIdentityFallsBackStaff() {
        String legacy = Jwts.builder()
                .subject("9")
                .id("legacy-jti")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        assertEquals(SecurityConstants.IDENTITY_STAFF, service.parse(legacy).identity());
    }
}
