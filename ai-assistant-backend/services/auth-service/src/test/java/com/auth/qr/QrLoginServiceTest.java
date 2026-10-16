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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 扫码登录服务测试（场景创建 / 轮询取令牌 / 手机端确认）
 *
 * @author yh
 * @date 2026-10-16
 */
class QrLoginServiceTest {

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

    @Test
    void 创建后轮询为等待中() {
        when(ops.get(anyString())).thenReturn("{\"status\":\"WAITING\"}");
        QrCreateVO created = service.create();
        assertThat(created.getSceneId()).isNotBlank();
        assertThat(service.poll(created.getSceneId()).getStatus()).isEqualTo("WAITING");
    }

    @Test
    void 确认后轮询返回令牌() {
        when(ops.get(anyString())).thenReturn("{\"status\":\"CONFIRMED\",\"identity\":\"staff\",\"userId\":7}");
        LoginVO vo = new LoginVO();
        vo.setToken("t");
        vo.setRefreshToken("r");
        when(issuer.issue("staff", 7L)).thenReturn(vo);

        QrPollVO poll = service.poll("scene1");
        assertThat(poll.getStatus()).isEqualTo("CONFIRMED");
        assertThat(poll.getToken()).isEqualTo("t");
    }

    @Test
    void 确认不存在的场景抛错() {
        when(ops.get(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.confirm("nope", "staff", 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 重复确认已确认场景被拒绝且不覆盖() {
        when(ops.get(anyString())).thenReturn("{\"status\":\"CONFIRMED\",\"identity\":\"staff\",\"userId\":7}");

        assertThatThrownBy(() -> service.confirm("scene1", "patient", 8L))
                .isInstanceOf(BusinessException.class);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 确认后轮询签发令牌并消费场景() {
        when(ops.get(anyString())).thenReturn("{\"status\":\"CONFIRMED\",\"identity\":\"staff\",\"userId\":7}");
        LoginVO vo = new LoginVO();
        vo.setToken("t");
        vo.setRefreshToken("r");
        when(issuer.issue("staff", 7L)).thenReturn(vo);

        QrPollVO poll = service.poll("scene1");
        assertThat(poll.getStatus()).isEqualTo("CONFIRMED");
        assertThat(poll.getToken()).isEqualTo("t");
        verify(redis).delete("qr:scene:scene1");
    }

    @Test
    void 签发失败不返回过期且不删除场景() {
        when(ops.get(anyString())).thenReturn("{\"status\":\"CONFIRMED\",\"identity\":\"staff\",\"userId\":7}");
        when(issuer.issue("staff", 7L)).thenThrow(new IllegalStateException("issue failed"));

        assertThatThrownBy(() -> service.poll("scene1"))
                .isInstanceOf(IllegalStateException.class);
        verify(redis, never()).delete(anyString());
    }
}
