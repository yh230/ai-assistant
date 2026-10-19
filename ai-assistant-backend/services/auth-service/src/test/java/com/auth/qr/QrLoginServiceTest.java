package com.auth.qr;

import com.auth.login.LoginIssuer;
import com.auth.qr.vo.QrCreateVO;
import com.auth.qr.vo.QrPollVO;
import com.auth.vo.LoginVO;
import com.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 扫码登录服务测试（场景创建 / 轮询取令牌 / 手机端确认）
 *
 * @author yh
 * @date 2026-10-19
 */
class QrLoginServiceTest {

    private static final String CONFIRMED_JSON =
            "{\"status\":\"CONFIRMED\",\"identity\":\"staff\",\"userId\":7}";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginIssuer issuer;
    private QrLoginService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        issuer = mock(LoginIssuer.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new QrLoginService(redis, issuer, new QrProperties());
    }

    /**
     * 轮询脚本桩：返回给定场景内容（null 表示场景不存在）
     */
    @SuppressWarnings("unchecked")
    private void stubPollScript(String json) {
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(json);
    }

    /**
     * 轮询脚本桩：按调用顺序返回多个结果，用于验证一次性消费
     */
    @SuppressWarnings("unchecked")
    private void stubPollScriptSequence(String first, String second) {
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(first, second);
    }

    /**
     * 确认脚本桩：返回给定的结果码
     */
    @SuppressWarnings("unchecked")
    private void stubConfirmScript(Long code) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(code);
    }

    private static LoginVO loginVO() {
        LoginVO vo = new LoginVO();
        vo.setToken("t");
        vo.setRefreshToken("r");
        return vo;
    }

    @Test
    void 创建后轮询为等待中() {
        stubPollScript("{\"status\":\"WAITING\"}");
        QrCreateVO created = service.create();
        assertThat(created.getSceneId()).isNotBlank();
        assertThat(service.poll(created.getSceneId()).getStatus()).isEqualTo("WAITING");
        verify(redis).execute(any(RedisScript.class), eq(List.of("qr:scene:" + created.getSceneId())));
    }

    /**
     * 一次性消费：第一次轮询拿到 CONFIRMED 并签发令牌，第二次轮询脚本返回 null → EXPIRED。
     * 证明 Java 依赖的是脚本的原子消费结果，而不是可重复读取的 GET。
     */
    @Test
    void pollConsumesConfirmedSceneOnlyOnce() {
        stubPollScriptSequence(CONFIRMED_JSON, null);
        when(issuer.issue("staff", 7L)).thenReturn(loginVO());

        QrPollVO first = service.poll("scene1");
        assertThat(first.getStatus()).isEqualTo("CONFIRMED");
        assertThat(first.getToken()).isEqualTo("t");
        assertThat(first.getRefreshToken()).isEqualTo("r");

        // 场景已被脚本 DEL，第二次读取拿不到内容
        QrPollVO second = service.poll("scene1");
        assertThat(second.getStatus()).isEqualTo("EXPIRED");
        assertThat(second.getToken()).isNull();

        // 只签发一次
        verify(issuer, times(1)).issue("staff", 7L);
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of("qr:scene:scene1")));
        // Java 侧不再自己做 delete
        verify(redis, never()).delete(anyString());
        verify(ops, never()).get(anyString());
    }

    @Test
    void 不存在的场景轮询为过期() {
        stubPollScript(null);
        assertThat(service.poll("scene1").getStatus()).isEqualTo("EXPIRED");
        verify(redis, never()).delete(anyString());
    }

    @Test
    void 无法解析的场景轮询为过期() {
        stubPollScript("not-json");
        assertThat(service.poll("scene1").getStatus()).isEqualTo("EXPIRED");
        verify(redis, never()).delete(anyString());
    }

    /**
     * 钉住实际执行的脚本：poll 必须是带 DEL 的轮询脚本，confirm 必须是带 KEEPTTL 的条件写入脚本。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pollAndConfirmUseExpectedScripts() {
        stubPollScriptSequence(CONFIRMED_JSON, null);
        when(issuer.issue("staff", 7L)).thenReturn(loginVO());
        service.poll("scene1");
        service.poll("scene1");

        ArgumentCaptor<RedisScript<String>> pollScriptCaptor =
                ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, times(2)).execute(pollScriptCaptor.capture(), eq(List.of("qr:scene:scene1")));
        assertThat(pollScriptCaptor.getAllValues()).allSatisfy(script -> {
            assertThat(script.getScriptAsString()).contains("redis.call('DEL'");
            assertThat(script.getScriptAsString()).doesNotContain("KEEPTTL");
        });

        stubConfirmScript(1L);
        service.confirm("scene1", "staff", 7L);

        ArgumentCaptor<RedisScript<Long>> confirmScriptCaptor =
                ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(confirmScriptCaptor.capture(), eq(List.of("qr:scene:scene1")),
                eq("staff"), eq("7"));
        String confirmScript = confirmScriptCaptor.getValue().getScriptAsString();
        assertThat(confirmScript).contains("KEEPTTL");
        assertThat(confirmScript).doesNotContain("redis.call('DEL'");
    }

    @Test
    void 确认不存在的场景抛错() {
        stubConfirmScript(-1L);
        assertThatThrownBy(() -> service.confirm("nope", "staff", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码已过期，请刷新后重试");
    }

    @Test
    void 确认状态异常的场景抛错() {
        stubConfirmScript(-2L);
        assertThatThrownBy(() -> service.confirm("scene1", "staff", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码状态异常，请刷新后重试");
    }

    @Test
    void 重复确认已确认场景被拒绝且不覆盖() {
        stubConfirmScript(0L);

        assertThatThrownBy(() -> service.confirm("scene1", "patient", 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码已确认，请勿重复操作");
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 未预期的脚本结果码抛状态异常() {
        stubConfirmScript(2L);
        assertThatThrownBy(() -> service.confirm("scene1", "staff", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码状态异常，请刷新后重试");
    }

    @Test
    void 确认身份为空时不执行脚本() {
        assertThatThrownBy(() -> service.confirm("scene1", " ", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码状态异常，请刷新后重试");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void 确认用户ID为空时不执行脚本() {
        assertThatThrownBy(() -> service.confirm("scene1", "staff", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("二维码状态异常，请刷新后重试");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void confirmUsesConditionalScript() {
        stubConfirmScript(0L);
        assertThatThrownBy(() -> service.confirm("scene1", "staff", 1L))
                .isInstanceOf(BusinessException.class);
        verify(redis).execute(any(RedisScript.class), eq(List.of("qr:scene:scene1")),
                eq("staff"), eq("1"));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 首次确认成功() {
        stubConfirmScript(1L);
        service.confirm("scene1", "staff", 7L);
        verify(redis).execute(any(RedisScript.class), eq(List.of("qr:scene:scene1")),
                eq("staff"), eq("7"));
    }

    @Test
    void 签发失败时场景已消费且异常透传() {
        stubPollScript(CONFIRMED_JSON);
        when(issuer.issue("staff", 7L)).thenThrow(new IllegalStateException("issue failed"));

        assertThatThrownBy(() -> service.poll("scene1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("issue failed");
        // 场景已被脚本原子消费，不删除、不复活
        verify(redis, never()).delete(anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
