package com.registration.controller.inner;

import com.api.dto.SourceInfoDTO;
import com.registration.vo.NumberSourceVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
}
