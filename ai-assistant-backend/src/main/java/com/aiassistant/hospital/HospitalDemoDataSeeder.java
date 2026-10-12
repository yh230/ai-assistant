package com.aiassistant.hospital;

import com.aiassistant.common.constant.RoleConstants;
import com.aiassistant.hospital.entity.Attendance;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.hospital.enums.AttendanceStatus;
import com.aiassistant.hospital.mapper.AttendanceMapper;
import com.aiassistant.hospital.mapper.DepartmentMapper;
import com.aiassistant.hospital.mapper.HospitalMapper;
import com.aiassistant.system.entity.SysRole;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.entity.SysUserRole;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import com.aiassistant.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * 实现 CommandLineRunner，首次启动时借助后端 BCryptPasswordEncoder 加密演示密码，
 * 将医院 / 科室 / 院长 / 科室主任 / 医生 / 护士 / 护工及近 30 天出勤数据灌入数据库，供前端数据权限验证。
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

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final AttendanceMapper attendanceMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PasswordEncoder passwordEncoder;

    /** 用户名 -> 用户ID */
    private final Map<String, Long> userIds = new HashMap<>();
    /** 角色编码 -> 角色ID */
    private final Map<String, Long> roleIds = new HashMap<>();

    @Override
    public void run(String... args) {
        if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, SENTINEL)) > 0) {
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
    }

    // ===== 内部成员 =====

    private record Emp(String username, String name, String roleCode, Long hospitalId, Long deptId) {
    }

    /**
     * 加载角色编码到角色 ID 的映射（库内不存在的角色直接跳过）
     */
    private void loadRoleIds() {
        for (String code : new String[]{RoleConstants.HOSPITAL_DIRECTOR, RoleConstants.DEPT_DIRECTOR,
                RoleConstants.DOCTOR, RoleConstants.NURSE, RoleConstants.CAREGIVER}) {
            SysRole r = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, code));
            if (r != null) {
                roleIds.put(code, r.getId());
            }
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
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
        u.setName(name);
        u.setPhone("");
        u.setEmail(username + "@demo.com");
        u.setStatus(1);
        u.setHospitalId(hospitalId);
        u.setDepartmentId(deptId);
        u.setCreateTime(LocalDateTime.now());
        u.setUpdateTime(LocalDateTime.now());
        sysUserMapper.insert(u);
        Long uid = u.getId();
        userIds.put(username, uid);
        Long rid = roleIds.get(roleCode);
        if (rid != null) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(uid);
            ur.setRoleId(rid);
            sysUserRoleMapper.insert(ur);
        }
        return uid;
    }

    /**
     * 为全部演示用户生成近 30 天出勤记录
     */
    private void generateAttendance() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Long uid : userIds.values()) {
            SysUser u = sysUserMapper.selectById(uid);
            for (int i = 0; i < 30; i++) {
                LocalDate d = today.minusDays(i);
                Attendance a = new Attendance();
                a.setUserId(uid);
                a.setHospitalId(u.getHospitalId());
                a.setDepartmentId(u.getDepartmentId());
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
