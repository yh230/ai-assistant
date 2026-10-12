package com.aiassistant.hospital.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.DimensionStatsSupport;
import com.aiassistant.common.support.NameLookup;
import com.aiassistant.hospital.dto.AttendanceQueryDTO;
import com.aiassistant.hospital.dto.AttendanceSaveDTO;
import com.aiassistant.hospital.entity.Attendance;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.hospital.enums.AttendanceStatus;
import com.aiassistant.hospital.mapper.AttendanceMapper;
import com.aiassistant.hospital.mapper.DepartmentMapper;
import com.aiassistant.hospital.mapper.HospitalMapper;
import com.aiassistant.hospital.service.AttendanceService;
import com.aiassistant.hospital.vo.AttendanceVO;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 出勤服务实现（核心数据权限）
 * <p>
 * 变更点（数据权限，满足「所有列表/统计/查询接口全部自动权限过滤」）：
 * <ul>
 *     <li>读：按 DataScope 对 attendance 表加 {@code department_id}（科室主任）或 {@code user_id}（本人）条件</li>
 *     <li>写：create/update/delete 前对目标员工做范围校验，越权直接拒绝（防绕过前端多写其他科室/他人）</li>
 * </ul>
 * hospital_id / department_id 由目标员工 sys_user 归属自动填充，前端不可传。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceMapper attendanceMapper;
    private final SysUserMapper userMapper;
    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询出勤记录（按当前登录人数据范围过滤）
     *
     * @param query 分页与筛选条件
     * @return 出勤分页结果
     */
    @Override
    public PageResult<AttendanceVO> pageAttendance(AttendanceQueryDTO query) {
        DataScope scope = dataScopeResolver.resolve();
        Page<Attendance> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<Attendance> qw = new LambdaQueryWrapper<>();
        scope.applyTo(qw, Attendance::getHospitalId, Attendance::getDepartmentId, Attendance::getUserId);

        // 院长维度的可选二次筛选
        if (scope.isHospital()) {
            // 院长：锁定本院，仅允许在本院内按科室二次筛选
            qw.eq(Attendance::getHospitalId, scope.getHospitalId());
            if (query.getDepartmentId() != null) {
                qw.eq(Attendance::getDepartmentId, query.getDepartmentId());
            }
        } else if (scope.isAll()) {
            if (query.getHospitalId() != null) {
                qw.eq(Attendance::getHospitalId, query.getHospitalId());
            }
            if (query.getDepartmentId() != null) {
                qw.eq(Attendance::getDepartmentId, query.getDepartmentId());
            }
        }

        if (query.getStatus() != null) {
            qw.eq(Attendance::getStatus, query.getStatus());
        }
        if (query.getDateStart() != null) {
            qw.ge(Attendance::getAttendDate, query.getDateStart());
        }
        if (query.getDateEnd() != null) {
            qw.le(Attendance::getAttendDate, query.getDateEnd());
        }
        // 姓名/账号关键字：先按 sys_user 名字解析员工ID集合
        if (StringUtils.hasText(query.getKeyword())) {
            List<Long> ids = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .like(SysUser::getName, query.getKeyword())
                            .or().like(SysUser::getUsername, query.getKeyword()))
                    .stream().map(SysUser::getId).collect(Collectors.toList());
            qw.in(Attendance::getUserId, ids.isEmpty() ? List.of(-1L) : ids);
        }
        qw.orderByDesc(Attendance::getAttendDate).orderByDesc(Attendance::getId);
        attendanceMapper.selectPage(page, qw);
        return PageResult.of(page, enrich(page.getRecords()));
    }

    @Override
    public AttendanceVO get(Long id) {
        Attendance a = attendanceMapper.selectById(id);
        if (a == null) {
            throw new BusinessException("出勤记录不存在");
        }
        assertReadable(a);
        return enrich(List.of(a)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(AttendanceSaveDTO dto) {
        SysUser user = loadUser(dto.getUserId());
        assertWritable(user);
        // 自动填充医院/科室（冗余），前端不可传
        Attendance a = new Attendance();
        copyFromDto(a, dto, user);
        // 同一人同一天唯一：已存在则转为更新
        Attendance existing = attendanceMapper.selectOne(new LambdaQueryWrapper<Attendance>()
                .eq(Attendance::getUserId, dto.getUserId())
                .eq(Attendance::getAttendDate, dto.getAttendDate()));
        if (existing != null) {
            a.setId(existing.getId());
            attendanceMapper.updateById(a);
        } else {
            a.setCreateTime(LocalDateTime.now());
            a.setUpdateTime(LocalDateTime.now());
            attendanceMapper.insert(a);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AttendanceSaveDTO dto) {
        Attendance a = attendanceMapper.selectById(id);
        if (a == null) {
            throw new BusinessException("出勤记录不存在");
        }
        // 以记录原归属员工做范围校验（防止把记录改到其他科室/他人）
        SysUser owner = loadUser(a.getUserId());
        assertWritable(owner);
        SysUser target = loadUser(dto.getUserId());
        assertWritable(target);
        copyFromDto(a, dto, target);
        a.setUpdateTime(LocalDateTime.now());
        attendanceMapper.updateById(a);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Attendance a = attendanceMapper.selectById(id);
        if (a == null) {
            throw new BusinessException("出勤记录不存在");
        }
        SysUser owner = loadUser(a.getUserId());
        assertWritable(owner);
        attendanceMapper.deleteById(id);
    }

    /**
     * 按维度统计出勤数据（按当前登录人数据范围过滤）
     *
     * @param dimension 统计维度（hospital/status/department）
     * @return 统计结果（含 dimension 与 rows）
     */
    @Override
    public Map<String, Object> stats(String dimension) {
        DataScope scope = dataScopeResolver.resolve();
        String groupColumn;
        if ("hospital".equals(dimension)) {
            groupColumn = "hospital_id";
        } else if ("status".equals(dimension)) {
            groupColumn = "status";
        } else {
            groupColumn = "department_id";
        }
        String whereSql = scope.toWhereSql("hospital_id", "department_id", "user_id");
        List<Map<String, Object>> rows = attendanceMapper.groupStats(groupColumn, whereSql);
        return DimensionStatsSupport.assemble(dimension, rows, false, dimId -> {
            if ("hospital".equals(dimension)) {
                Hospital h = hospitalMapper.selectById(dimId);
                return h == null ? "未知医院" : h.getName();
            }
            if ("status".equals(dimension)) {
                return statusText(dimId.intValue());
            }
            if (dimId == -1L) {
                return "院级/未分配科室";
            }
            Department d = departmentMapper.selectById(dimId);
            return d == null ? "未知科室" : d.getName();
        });
    }

    /**
     * 读权限：本范围不可见则拒绝
     *
     * @param a 出勤记录
     */
    private void assertReadable(Attendance a) {
        dataScopeResolver.resolve().assertCanAccess(a.getHospitalId(), a.getDepartmentId(), a.getUserId(), "无权查看该出勤记录");
    }

    /**
     * 写权限：目标员工必须在当前登录人数据范围内
     *
     * @param user 目标员工
     */
    private void assertWritable(SysUser user) {
        dataScopeResolver.resolve().assertCanAccess(user.getHospitalId(), user.getDepartmentId(), user.getId(), "无权操作该出勤数据");
    }

    private SysUser loadUser(Long userId) {
        SysUser u = userMapper.selectById(userId);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        return u;
    }

    /**
     * 按保存请求回填出勤记录，归属医院/科室由目标员工自动填充（前端不可传）
     *
     * @param a 出勤记录
     * @param dto 保存请求体
     * @param user 目标员工（提供归属医院/科室）
     */
    private void copyFromDto(Attendance a, AttendanceSaveDTO dto, SysUser user) {
        a.setUserId(dto.getUserId());
        // 自动填充冗余字段（前端不可传，防止越权写错科室）
        a.setHospitalId(user.getHospitalId());
        a.setDepartmentId(user.getDepartmentId());
        a.setAttendDate(dto.getAttendDate());
        a.setStatus(dto.getStatus() == null ? AttendanceStatus.PRESENT.getCode() : dto.getStatus());
        a.setCheckIn(dto.getCheckIn());
        a.setCheckOut(dto.getCheckOut());
        a.setRemark(dto.getRemark());
    }

    /**
     * 批量回填员工、医院、科室名称
     *
     * @param list 出勤记录列表
     * @return 出勤视图列表
     */
    private List<AttendanceVO> enrich(List<Attendance> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> userNames = NameLookup.namesByIds(
                list.stream().map(Attendance::getUserId).toList(), SysUser::getId, SysUser::getName, userMapper::selectBatchIds);
        Map<Long, String> hospitalNames = NameLookup.namesByIds(
                list.stream().map(Attendance::getHospitalId).toList(), Hospital::getId, Hospital::getName, hospitalMapper::selectBatchIds);
        Map<Long, String> deptNames = NameLookup.namesByIds(
                list.stream().map(Attendance::getDepartmentId).toList(), Department::getId, Department::getName, departmentMapper::selectBatchIds);
        return list.stream().map(a -> {
            AttendanceVO vo = new AttendanceVO();
            vo.setId(a.getId());
            vo.setUserId(a.getUserId());
            vo.setUserName(userNames.get(a.getUserId()));
            vo.setHospitalId(a.getHospitalId());
            vo.setHospitalName(a.getHospitalId() == null ? null : hospitalNames.get(a.getHospitalId()));
            vo.setDepartmentId(a.getDepartmentId());
            vo.setDepartmentName(a.getDepartmentId() == null ? null : deptNames.get(a.getDepartmentId()));
            vo.setAttendDate(a.getAttendDate());
            vo.setStatus(a.getStatus());
            vo.setStatusText(statusText(a.getStatus()));
            vo.setCheckIn(a.getCheckIn());
            vo.setCheckOut(a.getCheckOut());
            vo.setRemark(a.getRemark());
            vo.setCreateTime(a.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 出勤状态编码转文本
     *
     * @param status 状态编码（可空）
     * @return 出勤状态描述；未知或空返回「未知」
     */
    private String statusText(Integer status) {
        AttendanceStatus s = AttendanceStatus.of(status);
        return s == null ? "未知" : s.getDesc();
    }
}
