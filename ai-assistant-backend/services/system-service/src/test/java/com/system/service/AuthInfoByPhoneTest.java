package com.system.service;

import com.api.dto.AuthInfoDTO;
import com.system.mapper.SysUserMapper;
import com.system.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInfoByPhoneTest {

    @Test
    void byPhone_未绑定手机号返回null() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectByPhone(any())).thenReturn(null);
        UserServiceImpl service = new UserServiceImpl(mapper, null, null, null, null, null, null, null, null);

        AuthInfoDTO dto = service.authInfoByPhone("13800000000");

        assertThat(dto).isNull();
    }
}
