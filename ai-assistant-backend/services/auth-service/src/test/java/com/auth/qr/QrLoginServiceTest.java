package com.auth.qr;

import com.auth.login.LoginIssuer;
import com.auth.qr.vo.QrCreateVO;
import com.auth.qr.vo.QrPollVO;
import com.auth.vo.LoginVO;
import com.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
     * 确认脚本桩：返回给定的结果码
     */
    @SuppressWarnings("unchecked")
    private void stubConfirmScript(Long code) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(code);
    }

    @Test
    void 创建后轮询为等待中() {
        stubPollScript("{\"status\":\"WAITING\"}");
        QrCreateVO created = service.create();
        assertThat(created.getSceneId()).isNotBlank();
        assertThat(service.poll(created.getSceneId()).getStatus()).isEqualTo("WAITING");
        verify(redis).execute(any(RedisScript.class), eq(List.of("qr:scene:" + created.getSceneId())));
    }

    @Test
    void pollConsumesConfirmedSceneAtomically() {
        stubPollScript(CONFIRMED_JSON);
        LoginVO vo = new LoginVO();
        vo.setToken("t");
        vo.setRefreshToken("r");
        when(issuer.issue("staff", 7L)).thenReturn(vo);

        QrPollVO poll = service.poll("scene1");
        assertThat(poll.getStatus()).isEqualTo("CONFIRMED");
        assertThat(poll.getToken()).isEqualTo("t");
        assertThat(poll.getRefreshToken()).isEqualTo("r");
        // 场景由 Lua 脚本消费，Java 侧不再 delete
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
