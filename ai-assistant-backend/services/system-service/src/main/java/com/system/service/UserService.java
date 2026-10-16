package com.system.service;

import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.api.dto.ResetPasswordDTO;
import com.api.dto.UserBriefDTO;
import com.api.dto.UserCreateRequest;
import com.api.dto.UserPageRequest;
import com.api.dto.UserUpdateRequest;
import com.common.result.PageResult;
import com.system.vo.UserVO;

import java.util.List;

/**
 * 员工用户服务
 *
 * @author yh
 * @date 2026-10-12
 */
public interface UserService {

    /** 分页查询员工 */
    PageResult<UserVO> pageUsers(String keyword, long pageNum, long pageSize);

    /** 登录凭据校验并返回权限快照数据（凭据错误/禁用抛业务异常） */
    AuthInfoDTO authenticate(AuthRequest req);

    /** 按用户 ID 组装权限快照数据（不存在或停用抛业务异常） */
    AuthInfoDTO buildAuthInfo(Long userId);

    /**
     * 按手机号组装权限快照数据（未绑定或未启用返回 null）
     *
     * @param phone 手机号
     * @return 权限快照数据或 null
     */
    AuthInfoDTO authInfoByPhone(String phone);

    /** 按 ID 批量查询用户简要信息 */
    List<UserBriefDTO> batchBrief(List<Long> ids);

    /** 员工分页（过滤参数由调用方按数据权限传入） */
    PageResult<UserBriefDTO> pageBrief(UserPageRequest req);

    /** 按科室/医院查询医生列表（doctor 角色且启用） */
    List<UserBriefDTO> listDoctors(Long hospitalId, Long departmentId);

    /** 新增员工（含角色与医院/科室绑定），返回用户 ID */
    Long createUser(UserCreateRequest dto);

    /** 编辑员工 */
    void updateUser(Long id, UserUpdateRequest dto);

    /** 删除员工 */
    void deleteUser(Long id);

    /** 启用/禁用账号 */
    void changeStatus(Long id, Integer status);

    /** 重置密码 */
    void resetPassword(Long id, ResetPasswordDTO dto);

    /** 超级管理员强制踢某用户下线（撤销该用户 JWT） */
    void kickoutUser(Long id);

    /** 更新用户联系方式（手机号/邮箱） */
    void updateProfile(Long id, ProfileUpdateDTO dto);

    /** 修改密码（校验原密码） */
    void changePassword(Long id, ChangePasswordDTO dto);
}
