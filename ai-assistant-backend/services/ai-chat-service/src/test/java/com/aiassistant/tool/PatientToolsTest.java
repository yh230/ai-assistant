package com.aiassistant.tool;

import com.aiassistant.service.PendingActionService;
import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.client.UserClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 患者侧 AI 工具集单元测试（号源查询缺少所属医院时失败关闭）
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class PatientToolsTest {

    @Mock
    private OrgClient orgClient;
    @Mock
    private RegistrationClient registrationClient;
    @Mock
    private UserClient userClient;
    @Mock
    private PendingActionService pendingActionService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PatientTools patientTools;

    @Test
    void queryNumberSourcesFailsClosedWhenHospitalMissing() {
        ToolContext context = new ToolContext(new HashMap<>());

        assertThrows(RuntimeException.class, () ->
                patientTools.queryNumberSources(100L, "2026-10-20", null, context));

        verifyNoInteractions(registrationClient);
    }
}
