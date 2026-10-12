package com.aiassistant.system.service;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.dto.ResetPasswordDTO;
import com.aiassistant.system.dto.UserSaveDTO;
import com.aiassistant.system.dto.UserUpdateDTO;
import com.aiassistant.system.vo.UserVO;

/**
 * 员工用户服务
 *
 * @author yh
 * @date 2026-10-12
 */
public interface UserService {

    /** 分页查询员工 */
    PageResult<UserVO> pageUsers(String keyword, long pageNum, long pageSize);

    /** 新增员工 */
    void createUser(UserSaveDTO dto);

    /** 编辑员工 */
    void updateUser(Long id, UserUpdateDTO dto);

    /** 删除员工 */
    void deleteUser(Long id);

    /** 启用/禁用账号 */
    void changeStatus(Long id, Integer status);

    /** 重置密码 */
    void resetPassword(Long id, ResetPasswordDTO dto);

    /** 超级管理员强制踢某用户下线（撤销该用户 JWT） */
    void kickoutUser(Long id);
}
