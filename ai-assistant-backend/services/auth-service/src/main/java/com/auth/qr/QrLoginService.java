package com.auth.qr;

import com.auth.login.LoginIssuer;
import com.auth.qr.vo.QrCreateVO;
import com.auth.qr.vo.QrPollVO;
import com.auth.vo.LoginVO;
import com.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 扫码登录服务：创建场景 / 轮询取令牌 / 手机端确认（Redis 状态机）
 *
 * @author yh
 * @date 2026-10-16
 */
@Service
@RequiredArgsConstructor
public class QrLoginService {

    private static final String STATUS_WAITING = "WAITING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final StringRedisTemplate redis;
    private final LoginIssuer loginIssuer;
    private final QrProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建扫码场景
     *
     * @return 场景标识与有效期
     */
    public QrCreateVO create() {
        String sceneId = UUID.randomUUID().toString().replace("-", "");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", STATUS_WAITING);
        redis.opsForValue().set(key(sceneId), node.toString(), Duration.ofSeconds(props.getTtlSeconds()));
        return new QrCreateVO(sceneId, props.getTtlSeconds());
    }

    /**
     * 轮询场景状态（CONFIRMED 时签发令牌并一次性消费场景）
     *
     * @param sceneId 场景标识
     * @return 轮询结果
     */
    public QrPollVO poll(String sceneId) {
        String json = redis.opsForValue().get(key(sceneId));
        QrPollVO vo = new QrPollVO();
        if (json == null) {
            vo.setStatus(STATUS_EXPIRED);
            return vo;
        }
        try {
            var node = objectMapper.readTree(json);
            String status = node.path("status").asText();
            if (!STATUS_CONFIRMED.equals(status)) {
                vo.setStatus(STATUS_WAITING);
                return vo;
            }
            String identity = node.path("identity").asText();
            long userId = node.path("userId").asLong();
            redis.delete(key(sceneId));
            LoginVO login = loginIssuer.issue(identity, userId);
            vo.setStatus(STATUS_CONFIRMED);
            vo.setToken(login.getToken());
            vo.setRefreshToken(login.getRefreshToken());
            vo.setTokenType(login.getTokenType());
            return vo;
        } catch (Exception e) {
            vo.setStatus(STATUS_EXPIRED);
            return vo;
        }
    }

    /**
     * 手机端确认场景（写入确认者身份与用户 ID）
     *
     * @param sceneId 场景标识
     * @param identity 确认者身份（staff/patient）
     * @param userId 确认者用户 ID
     */
    public void confirm(String sceneId, String identity, Long userId) {
        String stored = redis.opsForValue().get(key(sceneId));
        if (stored == null) {
            throw new BusinessException("二维码已过期，请刷新后重试");
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", STATUS_CONFIRMED);
        node.put("identity", identity);
        node.put("userId", userId);
        Long ttl = redis.getExpire(key(sceneId));
        redis.opsForValue().set(key(sceneId), node.toString(),
                Duration.ofSeconds(ttl == null || ttl <= 0 ? props.getTtlSeconds() : ttl));
    }

    private String key(String sceneId) {
        return "qr:scene:" + sceneId;
    }
}
