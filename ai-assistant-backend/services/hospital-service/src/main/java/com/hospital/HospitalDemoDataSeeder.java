package com.hospital;

import com.api.client.UserClient;
import com.api.dto.RoleBriefDTO;
import com.api.dto.UserBriefDTO;
import com.api.dto.UserCreateRequest;
import com.api.dto.UserPageRequest;
import com.common.constant.RoleConstants;
import com.common.result.PageResult;
import com.common.result.Result;
import com.hospital.entity.Attendance;
import com.hospital.entity.Department;
import com.hospital.entity.Hospital;
import com.hospital.enums.AttendanceStatus;
import com.hospital.mapper.AttendanceMapper;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 医院模块演示数据播种（仅用于本地验证，非生产逻辑）
 *
 * 实现 CommandLineRunner，首次启动时将医院 / 科室 / 院长 / 科室主任 / 医生 / 护士 / 护工及近 30 天出勤数据灌入数据库，
 * 供前端数据权限验证。演示员工经用户中心 Feign 创建（BCrypt 加密在用户中心完成）。
 * 幂等：以哨兵用户名 {@code demo_root_h1} 是否存在为标记，已存在则跳过，避免重复播种。
 * 表结构无需改动（建表与扩列见 sql/hospital_schema.sql，库内表已就绪）。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalDemoDataSeeder implements CommandLineRunner {

    /** 统一演示密码，便于登录验证各角色 */
    private static final String DEMO_PASSWORD = "123456";
    /** 幂等哨兵用户名 */
    private static final String SENTINEL = "demo_root_h1";
    /** 哨兵存在性检索的页大小上限 */
    private static final long SENTINEL_PAGE_SIZE = 50;

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final AttendanceMapper attendanceMapper;
    private final UserClient userClient;

    /** 用户名 -> 用户ID */
    private final Map<String, Long> userIds = new HashMap<>();
    /** 用户ID -> 归属（医院/科室） */
    private final Map<Long, Emp> userBindings = new HashMap<>();
    /** 角色编码 -> 角色ID */
    private final Map<String, Long> roleIds = new HashMap<>();

    @Override
    public void run(String... args) {
        try {
            if (userExists(SENTINEL)) {
                log.info("[HospitalDemo] 演示数据已存在，跳过播种");
                return;
            }
            loadRoleIds();

            // 1. 医院
            Long h1 = insertHospital("市第一人民医院", "北京市朝阳区健康路1号", "010-11110000");
            Long h2 = insertHospital("市第二人民医院", "北京市海淀区和平路88号", "010-22220000");

            // 2. 科室（director_user_id 先置空，建完主任后回填）
            Map<String, Long> dept = new HashMap<>(8);
            dept.put("骨科", insertDepartment(h1, "骨科"));
            dept.put("风湿科", insertDepartment(h1, "风湿科"));
            dept.put("内科", insertDepartment(h1, "内科"));
            dept.put("外科", insertDepartment(h1, "外科"));
            dept.put("儿科", insertDepartment(h2, "儿科"));
            dept.put("妇产科", insertDepartment(h2, "妇产科"));

            // 3. 院长（hospital_director，全院视角）
            createUser("demo_root_h1", "院长-(市第一)", RoleConstants.HOSPITAL_DIRECTOR, h1, null);
            createUser("demo_root_h2", "院长-(市第二)", RoleConstants.HOSPITAL_DIRECTOR, h2, null);

            // 4. 科室主任（dept_director，本科室视角），并回填科室主任
            bindDirector(dept.get("骨科"), createUser("demo_ortho_dir", "骨科主任", RoleConstants.DEPT_DIRECTOR, h1, dept.get("骨科")));
            bindDirector(dept.get("风湿科"), createUser("demo_rheu_dir", "风湿科主任", RoleConstants.DEPT_DIRECTOR, h1, dept.get("风湿科")));
            bindDirector(dept.get("内科"), createUser("demo_internal_dir", "内科主任", RoleConstants.DEPT_DIRECTOR, h1, dept.get("内科")));
            bindDirector(dept.get("外科"), createUser("demo_surgery_dir", "外科主任", RoleConstants.DEPT_DIRECTOR, h1, dept.get("外科")));
            bindDirector(dept.get("儿科"), createUser("demo_peds_dir", "儿科主任", RoleConstants.DEPT_DIRECTOR, h2, dept.get("儿科")));
            bindDirector(dept.get("妇产科"), createUser("demo_obgyn_dir", "妇产科主任", RoleConstants.DEPT_DIRECTOR, h2, dept.get("妇产科")));

            // 5. 医生 / 护士 / 护工
            List<Emp> staff = new ArrayList<>();
            staff.add(new Emp("demo_ortho_doc1", "骨科医生-甲", RoleConstants.DOCTOR, h1, dept.get("骨科")));
            staff.add(new Emp("demo_ortho_doc2", "骨科医生-乙", RoleConstants.DOCTOR, h1, dept.get("骨科")));
            staff.add(new Emp("demo_rheu_doc1", "风湿科医生-甲", RoleConstants.DOCTOR, h1, dept.get("风湿科")));
            staff.add(new Emp("demo_internal_doc1", "内科医生-甲", RoleConstants.DOCTOR, h1, dept.get("内科")));
            staff.add(new Emp("demo_surgery_doc1", "外科医生-甲", RoleConstants.DOCTOR, h1, dept.get("外科")));
            staff.add(new Emp("demo_peds_doc1", "儿科医生-甲", RoleConstants.DOCTOR, h2, dept.get("儿科")));
            staff.add(new Emp("demo_obgyn_doc1", "妇产科医生-甲", RoleConstants.DOCTOR, h2, dept.get("妇产科")));
            staff.add(new Emp("demo_ortho_nurse1", "骨科护士-甲", RoleConstants.NURSE, h1, dept.get("骨科")));
            staff.add(new Emp("demo_rheu_nurse1", "风湿科护士-甲", RoleConstants.NURSE, h1, dept.get("风湿科")));
            staff.add(new Emp("demo_internal_nurse1", "内科护士-甲", RoleConstants.NURSE, h1, dept.get("内科")));
            staff.add(new Emp("demo_surgery_nurse1", "外科护士-甲", RoleConstants.NURSE, h1, dept.get("外科")));
            staff.add(new Emp("demo_peds_nurse1", "儿科护士-甲", RoleConstants.NURSE, h2, dept.get("儿科")));
            staff.add(new Emp("demo_obgyn_nurse1", "妇产科护士-甲", RoleConstants.NURSE, h2, dept.get("妇产科")));
            staff.add(new Emp("demo_ortho_care1", "骨科护工-甲", RoleConstants.CAREGIVER, h1, dept.get("骨科")));
            staff.forEach(e -> createUser(e.username(), e.name(), e.roleCode(), e.hospitalId(), e.deptId()));

            // 6. 出勤：为全部已建用户生成近 30 天记录
            generateAttendance();

            log.info("[HospitalDemo] 演示数据播种完成：2 家医院 / 6 科室 / {} 名用户", userIds.size());
        } catch (Exception e) {
            log.warn("[HospitalDemo] 演示数据播种失败（用户中心暂不可用？），跳过本次播种", e);
            return;
        }
    }

    // ===== 内部成员 =====

    private record Emp(String username, String name, String roleCode, Long hospitalId, Long deptId) {
    }

    /**
     * 经用户中心按哨兵用户名检索判断演示数据是否已存在（用户中心不可用视为未播种，由后续步骤报错）
     *
     * @param username 哨兵用户名
     * @return true 表示已存在
     */
    private boolean userExists(String username) {
        UserPageRequest req = new UserPageRequest();
        req.setPageNum(1);
        req.setPageSize(SENTINEL_PAGE_SIZE);
        req.setKeyword(username);
        Result<PageResult<UserBriefDTO>> result = userClient.page(req);
        return result != null && result.getCode() == 200 && result.getData() != null
                && result.getData().getTotal() > 0;
    }

    /**
     * 加载角色编码到角色 ID 的映射（库内不存在的角色直接跳过）
     */
    private void loadRoleIds() {
        Result<List<RoleBriefDTO>> result = userClient.roles();
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            log.warn("[HospitalDemo] 角色列表获取失败，演示员工将不带角色");
            return;
        }
        for (RoleBriefDTO role : result.getData()) {
            roleIds.put(role.getRoleCode(), role.getId());
        }
    }

    private Long insertHospital(String name, String address, String phone) {
        Hospital h = new Hospital();
        h.setName(name);
        h.setAddress(address);
        h.setPhone(phone);
        h.setStatus(1);
        h.setCreateTime(LocalDateTime.now());
        h.setUpdateTime(LocalDateTime.now());
        hospitalMapper.insert(h);
        return h.getId();
    }

    private Long insertDepartment(Long hospitalId, String name) {
        Department d = new Department();
        d.setHospitalId(hospitalId);
        d.setName(name);
        d.setDirectorUserId(null);
        d.setStatus(1);
        d.setCreateTime(LocalDateTime.now());
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.insert(d);
        return d.getId();
    }

    private void bindDirector(Long deptId, Long directorUid) {
        Department d = departmentMapper.selectById(deptId);
        d.setDirectorUserId(directorUid);
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(d);
    }

    private Long createUser(String username, String name, String roleCode, Long hospitalId, Long deptId) {
        UserCreateRequest req = new UserCreateRequest();
        req.setUsername(username);
        req.setPassword(DEMO_PASSWORD);
        req.setName(name);
        req.setPhone("");
        req.setEmail(username + "@demo.com");
        req.setStatus(1);
        req.setHospitalId(hospitalId);
        req.setDepartmentId(deptId);
        Long rid = roleIds.get(roleCode);
        req.setRoleIds(rid == null ? null : List.of(rid));
        Result<Long> result = userClient.create(req);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new IllegalStateException("演示员工创建失败: " + username);
        }
        Long uid = result.getData();
        userIds.put(username, uid);
        userBindings.put(uid, new Emp(username, name, roleCode, hospitalId, deptId));
        return uid;
    }

    /**
     * 为全部演示用户生成近 30 天出勤记录（归属医院/科室取自创建时的绑定）
     */
    private void generateAttendance() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Long uid : userIds.values()) {
            Emp emp = userBindings.get(uid);
            for (int i = 0; i < 30; i++) {
                LocalDate d = today.minusDays(i);
                Attendance a = new Attendance();
                a.setUserId(uid);
                a.setHospitalId(emp.hospitalId());
                a.setDepartmentId(emp.deptId());
                a.setAttendDate(d);
                int status = decideStatus(d);
                a.setStatus(status);
                a.setCheckIn(status == AttendanceStatus.LATE.getCode() ? "09:18" : "08:55");
                a.setCheckOut("17:30");
                a.setRemark(status == AttendanceStatus.PRESENT.getCode() ? "正常"
                        : (status == AttendanceStatus.LATE.getCode() ? "迟到"
                        : (status == AttendanceStatus.ABSENT.getCode() ? "缺勤" : "请假")));
                a.setCreateTime(now);
                a.setUpdateTime(now);
                attendanceMapper.insert(a);
            }
        }
    }

    /**
     * 出勤状态规则：周末请假；工作日偶发迟到/缺勤/请假，其余正常出勤
     *
     * @param d 日期
     * @return 出勤状态编码
     */
    private int decideStatus(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return AttendanceStatus.LEAVE.getCode();
        }
        int dom = d.getDayOfMonth();
        if (dom % 9 == 0) {
            return AttendanceStatus.ABSENT.getCode();
        }
        if (dom % 5 == 0) {
            return AttendanceStatus.LATE.getCode();
        }
        if (dom % 13 == 0) {
            return AttendanceStatus.LEAVE.getCode();
        }
        return AttendanceStatus.PRESENT.getCode();
    }
}
