package com.auth.login;

import com.api.client.PatientClient;
import com.api.dto.AuthInfoDTO;
import com.auth.snapshot.SnapshotManager;
import com.auth.vo.LoginVO;
import com.common.constant.SecurityConstants;
import com.common.jwt.JwtTokenService;
import com.common.jwt.RefreshTokenStore;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 登录签发器：按身份签发令牌对并重建权限快照
 *
 * @author yh
 * @date 2026-10-16
 */
@Component
@RequiredArgsConstructor
public class LoginIssuer {

    private final JwtTokenService jwtTokenService;
    private final RefreshTokenStore refreshTokenStore;
    private final SnapshotManager snapshotManager;
    private final PatientClient patientClient;

    /**
     * 签发令牌并写快照
     *
     * @param identity 身份（staff/patient）
     * @param userId 用户 ID
     * @return 登录结果（access + refresh）
     */
    public LoginVO issue(String identity, Long userId) {
        if (SecurityConstants.IDENTITY_PATIENT.equals(identity)) {
            Result<AuthInfoDTO> result = patientClient.authInfo(userId);
            snapshotManager.saveFromPatientLogin(userId, result == null ? null : result.getData());
        } else {
            snapshotManager.refresh(userId);
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtTokenService.issue(userId, identity));
        vo.setRefreshToken(refreshTokenStore.issue(identity, userId));
        return vo;
    }
}
