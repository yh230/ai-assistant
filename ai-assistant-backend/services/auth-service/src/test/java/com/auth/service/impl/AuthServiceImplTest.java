package com.auth.service.impl;

import com.api.client.PatientClient;
import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.UserBriefDTO;
import com.auth.dto.LoginRequest;
import com.auth.snapshot.SnapshotManager;
import com.auth.support.LoginAttemptGuard;
import com.auth.vo.LoginVO;
import com.common.auth.UserSnapshotStore;
import com.common.exception.BusinessException;
import com.common.jwt.JwtTokenService;
import com.common.jwt.RefreshTokenStore;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 认证服务测试（登录锁定校验/双令牌签发/刷新轮换/登出联动撤销）
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private UserClient userClient;
    @Mock private PatientClient patientClient;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private TokenRevocationService tokenRevocationService;
    @Mock private UserSnapshotStore userSnapshotStore;
    @Mock private SnapshotManager snapshotManager;
    @Mock private LoginAttemptGuard loginAttemptGuard;
    @Mock private RefreshTokenStore refreshTokenStore;
    @InjectMocks private AuthServiceImpl authService;

    private LoginRequest request(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    @Test
    void loginShouldRejectWhenLockedWithoutCallingUserCenter() {
        doThrow(new BusinessException("登录失败次数过多，账号已锁定，请稍后再试"))
                .when(loginAttemptGuard).assertNotLocked("staff", "admin");
        assertThrows(BusinessException.class, () -> authService.login(request("admin", "x")));
        verify(userClient, never()).authenticate(any());
    }

    @Test
    void loginShouldRecordFailureOnBadCredential() {
        when(userClient.authenticate(any(AuthRequest.class))).thenReturn(Result.fail(400, "账号或密码错误"));
        assertThrows(BusinessException.class, () -> authService.login(request("admin", "bad")));
        verify(loginAttemptGuard).recordFailure("staff", "admin");
        verify(loginAttemptGuard, never()).reset(anyString(), anyString());
    }

    @Test
    void loginShouldResetGuardOnSuccess() {
        AuthInfoDTO info = new AuthInfoDTO();
        UserBriefDTO user = new UserBriefDTO();
        user.setId(1L);
        info.setUser(user);
        when(userClient.authenticate(any(AuthRequest.class))).thenReturn(Result.success(info));
        when(jwtTokenService.issue(1L)).thenReturn("token-1");

        authService.login(request("admin", "ok"));

        verify(loginAttemptGuard).reset("staff", "admin");
        verify(snapshotManager).saveFromLogin(eq(1L), eq(info));
    }

    @Test
    void patientLoginShouldUsePatientIdentityKeys() {
        when(patientClient.authenticate(any(AuthRequest.class))).thenReturn(Result.fail(400, "账号或密码错误"));
        assertThrows(BusinessException.class, () -> authService.patientLogin(request("13800000000", "bad")));
        verify(loginAttemptGuard).recordFailure("patient", "13800000000");
    }

    @Test
    void loginShouldIssueRefreshToken() {
        AuthInfoDTO info = new AuthInfoDTO();
        UserBriefDTO user = new UserBriefDTO();
        user.setId(1L);
        info.setUser(user);
        when(userClient.authenticate(any(AuthRequest.class))).thenReturn(Result.success(info));
        when(jwtTokenService.issue(1L)).thenReturn("token-1");
        when(refreshTokenStore.issue("staff", 1L)).thenReturn("rt-1");

        LoginVO vo = authService.login(request("admin", "ok"));

        assertEquals("rt-1", vo.getRefreshToken());
    }

    @Test
    void refreshShouldRotateTokensWhenValid() {
        when(refreshTokenStore.consume("old-rt"))
                .thenReturn(new RefreshTokenStore.RefreshTokenInfo("staff", 1L, 1000L));
        when(tokenRevocationService.kickoutAt("staff", 1L)).thenReturn(0L);
        when(jwtTokenService.issue(1L, "staff")).thenReturn("new-access");
        when(refreshTokenStore.issue("staff", 1L)).thenReturn("new-rt");

        LoginVO vo = authService.refresh("old-rt");

        assertEquals("new-access", vo.getToken());
        assertEquals("new-rt", vo.getRefreshToken());
    }

    @Test
    void refreshShouldRejectWhenTokenMissing() {
        when(refreshTokenStore.consume("bad")).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.refresh("bad"));
        verify(refreshTokenStore, never()).issue(any(), any());
    }

    @Test
    void refreshShouldRejectWhenKickedOut() {
        when(refreshTokenStore.consume("rt"))
                .thenReturn(new RefreshTokenStore.RefreshTokenInfo("patient", 9L, 1000L));
        when(tokenRevocationService.kickoutAt("patient", 9L)).thenReturn(2000L);

        assertThrows(BusinessException.class, () -> authService.refresh("rt"));
        verify(refreshTokenStore, never()).issue(any(), any());
    }

    @Test
    void logoutShouldRevokeRefreshToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer jwt-token");
        when(jwtTokenService.parse("jwt-token"))
                .thenReturn(new JwtTokenService.JwtPayload(1L, "staff", "jti-1", 0L, 9999999999999L));

        authService.logout(request, "rt-1");

        verify(tokenRevocationService).revokeToken("jti-1", 9999999999999L);
        verify(refreshTokenStore).revoke("rt-1");
    }
}
