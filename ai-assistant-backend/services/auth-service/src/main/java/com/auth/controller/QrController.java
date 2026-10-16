package com.auth.controller;

import com.api.dto.QrConfirmRequest;
import com.auth.qr.QrLoginService;
import com.auth.qr.vo.QrCreateVO;
import com.auth.qr.vo.QrPollVO;
import com.common.auth.UserSnapshot;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 扫码登录接口：创建场景（PC）、轮询取令牌（PC）、确认（手机端，需登录）
 *
 * @author yh
 * @date 2026-10-16
 */
@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrLoginService qrLoginService;

    /**
     * 创建扫码场景
     *
     * @return 场景标识与有效期
     */
    @PostMapping("/create")
    public Result<QrCreateVO> create() {
        return Result.success(qrLoginService.create());
    }

    /**
     * 轮询扫码结果
     *
     * @param sceneId 场景标识
     * @return 轮询结果（确认后携带令牌对）
     */
    @GetMapping("/poll")
    public Result<QrPollVO> poll(@RequestParam("sceneId") String sceneId) {
        return Result.success(qrLoginService.poll(sceneId));
    }

    /**
     * 手机端确认登录（需已登录，身份决定 PC 最终登录身份）
     *
     * @param request 确认入参
     * @return 空结果
     */
    @PostMapping("/confirm")
    public Result<Void> confirm(@Valid @RequestBody QrConfirmRequest request) {
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        if (snapshot == null || snapshot.getUserId() == null || snapshot.getIdentity() == null) {
            throw new BusinessException("未登录，无法确认");
        }
        qrLoginService.confirm(request.getSceneId(), snapshot.getIdentity(), snapshot.getUserId());
        return Result.success();
    }
}
