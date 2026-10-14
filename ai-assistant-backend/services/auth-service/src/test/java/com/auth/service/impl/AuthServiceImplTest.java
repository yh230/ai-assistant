package com.auth.service.impl;

import com.api.client.PatientClient;
import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.UserBriefDTO;
import com.auth.dto.LoginRequest;
import com.auth.snapshot.SnapshotManager;
import com.auth.support.LoginAttemptGuard;
import com.common.auth.UserSnapshotStore;
import com.common.exception.BusinessException;
import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 认证服务登录接入测试（锁定校验/失败计数/成功清零）
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
}
