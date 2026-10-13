package com.system.patient.service;

import com.api.client.OrgClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.OrgBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.system.patient.dto.PatientRegisterDTO;
import com.system.patient.entity.PatientUser;
import com.system.patient.mapper.PatientUserMapper;
import com.system.patient.service.impl.PatientServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock private PatientUserMapper patientUserMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OrgClient orgClient;
    @InjectMocks private PatientServiceImpl patientService;

    private PatientRegisterDTO registerDto() {
        PatientRegisterDTO dto = new PatientRegisterDTO();
        dto.setPhone("13800000001");
        dto.setPassword("123456");
        dto.setName("张三");
        dto.setHospitalId(1L);
        return dto;
    }

    @Test
    void registerRejectsDuplicatePhone() {
        when(patientUserMapper.selectCount(any())).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.register(registerDto()));
        assertEquals("该手机号已注册", ex.getMessage());
    }

    @Test
    void registerRejectsMissingHospital() {
        when(patientUserMapper.selectCount(any())).thenReturn(0L);
        when(orgClient.hospitals(List.of(1L))).thenReturn(Result.success(List.of()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.register(registerDto()));
        assertEquals("所选医院不存在", ex.getMessage());
    }

    @Test
    void registerEncodesPasswordAndInserts() {
        when(patientUserMapper.selectCount(any())).thenReturn(0L);
        OrgBriefDTO hospital = new OrgBriefDTO();
        hospital.setId(1L);
        when(orgClient.hospitals(List.of(1L))).thenReturn(Result.success(List.of(hospital)));
        when(passwordEncoder.encode("123456")).thenReturn("$2a$hash");
        // 模拟 MyBatis-Plus 插入后回填自增主键的真实行为
        when(patientUserMapper.insert(any(PatientUser.class))).thenAnswer(inv -> {
            inv.getArgument(0, PatientUser.class).setId(1L);
            return 1;
        });
        assertEquals(1L, patientService.register(registerDto()));
    }

    @Test
    void authenticateRejectsWrongPassword() {
        PatientUser user = new PatientUser();
        user.setId(1L); user.setPhone("13800000001");
        user.setPassword("$2a$hash"); user.setStatus(1); user.setHospitalId(1L); user.setName("张三");
        when(patientUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);
        AuthRequest req = new AuthRequest();
        req.setUsername("13800000001"); req.setPassword("bad");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.authenticate(req));
        assertEquals("手机号或密码错误", ex.getMessage());
    }

    @Test
    void authenticateRejectsDisabledAccount() {
        PatientUser user = new PatientUser();
        user.setId(1L); user.setStatus(0);
        when(patientUserMapper.selectOne(any())).thenReturn(user);
        AuthRequest req = new AuthRequest();
        req.setUsername("13800000001"); req.setPassword("123456");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> patientService.authenticate(req));
        assertEquals("账号已被禁用", ex.getMessage());
    }

    @Test
    void authenticateBuildsSnapshotFromPatient() {
        PatientUser user = new PatientUser();
        user.setId(9L); user.setPhone("13800000001"); user.setName("张三");
        user.setPassword("$2a$hash"); user.setStatus(1); user.setHospitalId(2L);
        when(patientUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("123456", "$2a$hash")).thenReturn(true);
        AuthRequest req = new AuthRequest();
        req.setUsername("13800000001"); req.setPassword("123456");
        AuthInfoDTO info = patientService.authenticate(req);
        assertEquals(9L, info.getUser().getId());
        assertEquals(2L, info.getHospitalId());
        assertTrue(info.getRoles().isEmpty());
        assertTrue(info.getPerms().isEmpty());
    }
}
