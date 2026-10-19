package com.auth.qr;

import com.auth.login.LoginIssuer;
import com.auth.qr.vo.QrCreateVO;
import com.auth.qr.vo.QrPollVO;
import com.auth.vo.LoginVO;
import com.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 扫码登录服务：创建场景 / 轮询取令牌 / 手机端确认（Redis 状态机）
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrLoginService {

    private static final String STATUS_WAITING = "WAITING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * 轮询脚本：GET 后若为 CONFIRMED 则在脚本内 DEL，保证多个并发轮询只有一个能拿到已确认场景
     */
    private static final DefaultRedisScript<String> POLL_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            local ok, node = pcall(cjson.decode, v)
            if not ok or type(node) ~= 'table' then return v end
            if node.status ~= 'CONFIRMED' then return v end
            redis.call('DEL', KEYS[1])
            return v
            """, String.class);

    /**
     * 确认脚本：仅允许 WAITING -> CONFIRMED，使用 KEEPTTL 保留原有过期时间
     */
    private static final DefaultRedisScript<Long> CONFIRM_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return -1 end
            local ok, node = pcall(cjson.decode, v)
            if not ok or type(node) ~= 'table' or node.status == nil then return -2 end
            if node.status ~= 'WAITING' then return 0 end
            node.status = 'CONFIRMED'
            node.identity = ARGV[1]
            node.userId = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], cjson.encode(node), 'KEEPTTL')
            return 1
            """, Long.class);

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
     * 轮询场景状态（CONFIRMED 时签发令牌，场景由脚本一次性消费）
     *
     * @param sceneId 场景标识
     * @return 轮询结果
     */
    public QrPollVO poll(String sceneId) {
        String json = redis.execute(POLL_SCRIPT, List.of(key(sceneId)));
        QrPollVO vo = new QrPollVO();
        if (json == null) {
            vo.setStatus(STATUS_EXPIRED);
            return vo;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            vo.setStatus(STATUS_EXPIRED);
            return vo;
        }
        String status = node.path("status").asText();
        if (!STATUS_CONFIRMED.equals(status)) {
            vo.setStatus(STATUS_WAITING);
            return vo;
        }
        String identity = node.path("identity").asText();
        long userId = node.path("userId").asLong();
        LoginVO login;
        try {
            login = loginIssuer.issue(identity, userId);
        } catch (RuntimeException e) {
            // 场景已被 Lua 脚本原子消费，签发失败时不复活场景，仅记录告警并向上抛出
            log.warn("扫码场景已消费但令牌签发失败，sceneId={}", sceneId, e);
            throw e;
        }
        vo.setStatus(STATUS_CONFIRMED);
        vo.setToken(login.getToken());
        vo.setRefreshToken(login.getRefreshToken());
        vo.setTokenType(login.getTokenType());
        return vo;
    }

    /**
     * 手机端确认场景（仅 WAITING 可确认，写入确认者身份与用户 ID）
     *
     * @param sceneId 场景标识
     * @param identity 确认者身份（staff/patient）
     * @param userId 确认者用户 ID
     */
    public void confirm(String sceneId, String identity, Long userId) {
        if (identity == null || identity.isBlank() || userId == null) {
            // 防御性校验：不允许把空的确认者信息写入场景
            throw new BusinessException("二维码状态异常，请刷新后重试");
        }
        Long result = redis.execute(CONFIRM_SCRIPT, List.of(key(sceneId)),
                identity, String.valueOf(userId));
        long code = result == null ? -1L : result;
        if (code == -1L) {
            throw new BusinessException("二维码已过期，请刷新后重试");
        }
        if (code == -2L) {
            throw new BusinessException("二维码状态异常，请刷新后重试");
        }
        if (code == 0L) {
            throw new BusinessException("二维码已确认，请勿重复操作");
        }
        if (code != 1L) {
            // 脚本返回了未预期的结果码，按状态异常处理
            throw new BusinessException("二维码状态异常，请刷新后重试");
        }
    }

    private String key(String sceneId) {
        return "qr:scene:" + sceneId;
    }
}
