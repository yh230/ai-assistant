package com.aiassistant.tool;

import com.aiassistant.service.PendingActionService;
import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.client.UserClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 患者侧 AI 工具集单元测试（参数校验与失败关闭：非法输入只返回结构化错误，不触达业务服务）
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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PatientTools patientTools;

    private static final LocalDate TODAY = LocalDate.now();

    private final ObjectMapper parser = new ObjectMapper();

    @Test
    void queryNumberSourcesFailsClosedWhenHospitalMissing() throws Exception {
        ToolContext context = new ToolContext(new HashMap<>());

        Object result = patientTools.queryNumberSources(100L, TODAY.toString(), null, context);

        assertTrue(errorMessage(result).contains("所属医院"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void queryNumberSourcesRejectsPastDate() throws Exception {
        ToolContext context = contextWithHospital();

        Object result = patientTools.queryNumberSources(100L, TODAY.minusDays(1).toString(), 1, context);

        assertTrue(errorMessage(result).contains("不能早于今天"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void queryNumberSourcesRejectsDateBeyondAdvanceWindow() throws Exception {
        ToolContext context = contextWithHospital();

        Object result = patientTools.queryNumberSources(100L, TODAY.plusDays(15).toString(), 1, context);

        assertTrue(errorMessage(result).contains("14"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void queryNumberSourcesRejectsMalformedDate() throws Exception {
        ToolContext context = contextWithHospital();

        Object result = patientTools.queryNumberSources(100L, "2026/10/20", 1, context);

        assertTrue(errorMessage(result).contains("格式"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void queryNumberSourcesRejectsInvalidPeriod() throws Exception {
        ToolContext context = contextWithHospital();

        Object result = patientTools.queryNumberSources(100L, TODAY.plusDays(1).toString(), 3, context);

        assertTrue(errorMessage(result).contains("时段"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void queryNumberSourcesRejectsNonPositiveDepartment() throws Exception {
        ToolContext context = contextWithHospital();

        Object result = patientTools.queryNumberSources(0L, TODAY.plusDays(1).toString(), 1, context);

        assertTrue(errorMessage(result).contains("科室ID"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void bookAppointmentRejectsNonPositiveDoctor() throws Exception {
        ToolContext context = contextWithSession();

        String result = patientTools.bookAppointment(100L, 0L, TODAY.plusDays(1).toString(), 1, null, context);

        assertEquals("医生ID不合法，请先查询该科室号源获取正确医生ID",
                parser.readTree(result).get("error").asText());
        verifyNoInteractions(pendingActionService);
    }

    @Test
    void bookAppointmentRejectsMissingPeriod() throws Exception {
        ToolContext context = contextWithSession();

        String result = patientTools.bookAppointment(100L, 200L, TODAY.plusDays(1).toString(), null, null, context);

        assertTrue(parser.readTree(result).get("error").asText().contains("时段"));
        verifyNoInteractions(pendingActionService);
    }

    @Test
    void bookAppointmentRejectsNonPositiveSeqNo() throws Exception {
        ToolContext context = contextWithSession();

        String result = patientTools.bookAppointment(100L, 200L, TODAY.plusDays(1).toString(), 1, 0, context);

        assertTrue(parser.readTree(result).get("error").asText().contains("序号"));
        verifyNoInteractions(pendingActionService);
    }

    @Test
    void bookAppointmentRejectsDateOutOfWindow() throws Exception {
        ToolContext context = contextWithSession();

        String result = patientTools.bookAppointment(100L, 200L, TODAY.plusDays(20).toString(), 1, null, context);

        assertTrue(parser.readTree(result).get("error").asText().contains("14"));
        verifyNoInteractions(pendingActionService);
    }

    @Test
    void cancelAppointmentRejectsNonPositiveRegistrationId() throws Exception {
        ToolContext context = contextWithSession();

        String result = patientTools.cancelAppointment(0L, context);

        assertTrue(parser.readTree(result).get("error").asText().contains("预约记录ID"));
        verifyNoInteractions(pendingActionService);
    }

    /**
     * 断言返回值是含 error 字段的 JSON 字符串并返回错误文案
     *
     * @param result 工具返回值
     * @return 错误文案
     * @throws Exception JSON 解析失败
     */
    private String errorMessage(Object result) throws Exception {
        String json = assertInstanceOf(String.class, result);
        JsonNode node = parser.readTree(json);
        assertTrue(node.hasNonNull("error"), "非法参数应返回结构化错误 JSON：" + json);
        return node.get("error").asText();
    }

    /**
     * 构造含所属医院的工具上下文
     *
     * @return 工具上下文
     */
    private ToolContext contextWithHospital() {
        HashMap<String, Object> values = new HashMap<>();
        values.put("hospitalId", 1L);
        return new ToolContext(values);
    }

    /**
     * 构造含医院、会话与患者身份的工具上下文
     *
     * @return 工具上下文
     */
    private ToolContext contextWithSession() {
        HashMap<String, Object> values = new HashMap<>();
        values.put("hospitalId", 1L);
        values.put("sessionId", "session-1");
        values.put("patientId", 10L);
        return new ToolContext(values);
    }
}
