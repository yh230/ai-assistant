package com.system.patient.service;

import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.UserBriefDTO;
import com.system.patient.dto.PatientRegisterDTO;

import java.util.List;

/**
 * 患者账号服务
 *
 * @author yh
 * @date 2026-10-13
 */
public interface PatientService {

    /**
     * 注册患者账号（手机号唯一、医院存在性校验，密码 BCrypt 加密入库）
     *
     * @param dto 注册入参（手机号/密码/姓名/医院）
     * @return 新账号 ID
     */
    Long register(PatientRegisterDTO dto);

    /**
     * 患者登录凭据校验并返回快照数据（凭据错误/禁用抛业务异常）
     *
     * @param req 登录入参（username 为手机号）
     * @return 权限快照数据
     */
    AuthInfoDTO authenticate(AuthRequest req);

    /**
     * 按患者 ID 组装快照数据（不存在或禁用返回 null）
     *
     * @param id 患者 ID
     * @return 权限快照数据或 null
     */
    AuthInfoDTO authInfo(Long id);

    /**
     * 按手机号组装快照数据（未绑定或未启用返回 null）
     *
     * @param phone 手机号
     * @return 权限快照数据或 null
     */
    AuthInfoDTO authInfoByPhone(String phone);

    /**
     * 按 ID 批量查询患者简要信息
     *
     * @param ids 患者 ID 集合
     * @return 患者简要信息列表
     */
    List<UserBriefDTO> batchBrief(List<Long> ids);
}
