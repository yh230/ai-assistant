package com.registration.controller.inner;

import com.api.dto.RegistrationBriefDTO;
import com.api.dto.SourceInfoDTO;
import com.registration.vo.NumberSourceVO;
import com.registration.vo.RegistrationVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InnerRegistrationControllerTest {

    @Test
    void mapsNumberSourceVoToSourceInfoDto() {
        NumberSourceVO vo = new NumberSourceVO();
        vo.setScheduleId(3L);
        vo.setDoctorUserId(10L);
        vo.setDoctorName("王医生");
        vo.setScheduleDate(LocalDate.of(2026, 10, 15));
        vo.setPeriod(1);
        vo.setPeriodText("上午");
        vo.setTotalLimit(20);
        vo.setUsedCount(5);
        vo.setRemainingCount(15);

        List<SourceInfoDTO> dtos = InnerRegistrationController.toSourceInfoList(List.of(vo));

        assertEquals(1, dtos.size());
        SourceInfoDTO dto = dtos.get(0);
        assertEquals(3L, dto.getScheduleId());
        assertEquals("王医生", dto.getDoctorName());
        assertEquals("2026-10-15", dto.getScheduleDate());
        assertEquals(15, dto.getRemainingCount());
    }

    @Test
    void mapsRegistrationVoToBriefDto() {
        RegistrationVO vo = new RegistrationVO();
        vo.setId(7L);
        vo.setRegistrationNo("GH1-3-202610150101");
        vo.setDepartmentId(2L);
        vo.setDepartmentName("心内科");
        vo.setDoctorName("李医生");
        vo.setScheduleDate(LocalDate.of(2026, 10, 15));
        vo.setPeriod(1);
        vo.setPeriodText("上午");
        vo.setSeqNo(3);
        vo.setStatus(1);
        vo.setStatusText("已预约");
        vo.setExpireTime(LocalDateTime.of(2026, 10, 15, 9, 30));

        List<RegistrationBriefDTO> dtos = InnerRegistrationController.toBriefList(List.of(vo));

        assertEquals(1, dtos.size());
        RegistrationBriefDTO dto = dtos.get(0);
        assertEquals(7L, dto.getId());
        assertEquals("GH1-3-202610150101", dto.getRegistrationNo());
        assertEquals(2L, dto.getDepartmentId());
        assertEquals("心内科", dto.getDepartmentName());
        assertEquals("李医生", dto.getDoctorName());
        assertEquals("2026-10-15", dto.getScheduleDate());
        assertEquals(1, dto.getPeriod());
        assertEquals("上午", dto.getPeriodText());
        assertEquals(3, dto.getSeqNo());
        assertEquals(1, dto.getStatus());
        assertEquals("已预约", dto.getStatusText());
        assertEquals("2026-10-15T09:30", dto.getExpireTime());
    }
}
