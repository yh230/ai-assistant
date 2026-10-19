package com.common.security;

import com.common.auth.UserSnapshot;
import com.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 数据权限解析器单测（员工入口未登录必须拒绝，内部解析保持 ALL）
 *
 * @author yh
 * @date 2026-10-19
 */
class DataScopeResolverTest {

    @Test
    void requiredRejectsAnonymous() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentSnapshot).thenReturn(null);
            assertThrows(BusinessException.class, () -> new DataScopeResolver().resolveRequired());
        }
    }

    @Test
    void resolveKeepsAllForInternalCalls() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentSnapshot).thenReturn(null);
            DataScope scope = new DataScopeResolver().resolve();
            assertTrue(scope.isAll(), "内部/定时任务调用应保持 ALL");
        }
    }

    @Test
    void requiredReturnsScopeForLoggedInUser() {
        UserSnapshot snapshot = mock(UserSnapshot.class);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentSnapshot).thenReturn(snapshot);
            when(snapshot.getRoles()).thenReturn(List.of("doctor"));
            when(snapshot.getUserId()).thenReturn(9L);
            DataScope scope = new DataScopeResolver().resolveRequired();
            assertEquals(DataScope.Type.SELF, scope.getType());
            assertEquals(9L, scope.getUserId());
        }
    }
}
