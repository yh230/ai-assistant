package com.registration.service;

import com.common.result.PageResult;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.dto.RegistrationQueryDTO;
import com.registration.dto.WalkInRegisterDTO;
import com.registration.vo.RegistrationVO;

import java.util.List;
import java.util.Map;

/**
 * 挂号 / 预约 / 取号核心服务（状态机驱动）。
 * <p>公开接口（患者免登录）与员工接口（按角色 + 数据权限）分离。</p>
 */
public interface RegistrationService {

    // ===== 公开（患者 / 自助机查询本人） =====

    /** 患者线上预约：选号源→校验余量→锁定→生成已预约记录（待支付） */
    RegistrationVO createAppointment(AppointmentCreateDTO dto);

    /** 患者确认支付（模拟支付成功），状态仍「已预约」，仅更新 pay_status */
    RegistrationVO confirmPay(Long id, String idCard);

    /** 患者查询本人预约（凭手机号/身份证/预约号） */
    List<RegistrationVO> patientQuery(String phone, String idCard, String registrationNo);

    /** 患者取消本人未取号预约：释放号源 */
    RegistrationVO patientCancel(Long id, String idCard, String reason);

    // ===== 员工（鉴权 + 数据权限） =====

    /** 挂号记录分页（按数据范围自动过滤） */
    PageResult<RegistrationVO> page(RegistrationQueryDTO q);

    /** 详情（凭证打印用） */
    RegistrationVO get(Long id);

    /** 现场窗口挂号：直接生成「已取号」挂号单 */
    RegistrationVO walkIn(WalkInRegisterDTO dto);

    /** 取号：已预约→已取号，生成就诊号/凭证 */
    RegistrationVO take(Long id);

    /** 退号：已取号→已退号，退款 + 号源回收（须未就诊） */
    RegistrationVO refund(Long id, String reason);

    /** 标记就诊：已取号→已就诊（医生，本人患者） */
    RegistrationVO treat(Long id);

    /** 挂号统计（doctor/department/status/date 维度，按数据范围过滤） */
    Map<String, Object> stats(String dimension);
}
