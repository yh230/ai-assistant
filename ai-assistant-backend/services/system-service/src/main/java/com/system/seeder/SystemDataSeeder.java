package com.system.seeder;

import com.common.constant.RoleConstants;
import com.system.entity.SysMenu;
import com.system.entity.SysRole;
import com.system.entity.SysRoleMenu;
import com.system.entity.SysUser;
import com.system.mapper.SysMenuMapper;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysRoleMenuMapper;
import com.system.mapper.SysUserMapper;
import com.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 初始数据播种：首次启动时创建默认管理员账号、管理员角色与基础菜单权限
 * <p>
 * 表结构需提前执行 {@code src/main/resources/sql/rbac_schema.sql} 创建。
 * 若数据已存在则跳过，保证幂等。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemDataSeeder implements CommandLineRunner {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final PasswordEncoder passwordEncoder;

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String ADMIN_ROLE_CODE = RoleConstants.ADMIN;

    // 医院模块固定岗位角色（角色自带数据权限规则，由 DataScopeResolver 解释）
    private static final String[] HOSPITAL_ROLE_CODES = {
            RoleConstants.HOSPITAL_DIRECTOR, RoleConstants.DEPT_DIRECTOR, RoleConstants.DOCTOR,
            RoleConstants.NURSE, RoleConstants.CAREGIVER};
    private static final String[] HOSPITAL_ROLE_NAMES = {"院长", "科室主任", "医生", "护士", "护工"};

    @Override
    public void run(String... args) {
        Long adminUserId = ensureAdminUser();
        Long adminRoleId = ensureAdminRole();
        ensureUserRole(adminUserId, adminRoleId);

        long menuCount = menuMapper.selectCount(new LambdaQueryWrapper<>());
        if (menuCount == 0) {
            seedMenus();
            log.info("RBAC 初始菜单已写入");
        }
        ensureSecurityModule(); // 先于 admin 补链执行，使新菜单当次启动即可见
        // 每次启动都幂等补齐 admin 角色与全部菜单的关联，
        // 避免「菜单已存在但未关联 admin」导致超级管理员权限缺失。
        ensureAdminRoleAllMenus(adminRoleId);
        // 医院科室人员 & 出勤模块的固定岗位角色与菜单播种
        ensureHospitalModule();
        // 门诊挂号取号模块的固定角色与菜单播种
        ensureRegistrationModule();
    }

    /**
     * 确保超级管理员（admin 角色）关联全部菜单，幂等补齐缺失关联。
     */
    private void ensureAdminRoleAllMenus(Long adminRoleId) {
        List<SysMenu> allMenus = menuMapper.selectList(new LambdaQueryWrapper<>());
        Set<Long> linkedMenuIds = roleMenuMapper.listByRoleId(adminRoleId).stream()
                .map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
        int added = 0;
        for (SysMenu menu : allMenus) {
            if (!linkedMenuIds.contains(menu.getId())) {
                roleMenuMapper.insertRelation(adminRoleId, menu.getId());
                added++;
            }
        }
        if (added > 0) {
            log.info("已为 admin 角色补齐 {} 个菜单权限关联（超级管理员拥有全部菜单权限）", added);
        }
    }

    /**
     * 播种安全模块菜单（操作日志页），幂等
     */
    private void ensureSecurityModule() {
        if (menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getPerms, "system:operlog:list")) > 0) {
            return;
        }
        SysMenu parent = menuMapper.selectOne(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, 0L)
                .eq(SysMenu::getMenuName, "系统管理")
                .last("LIMIT 1"));
        if (parent == null) {
            log.warn("未找到「系统管理」菜单，跳过操作日志菜单播种");
            return;
        }
        menuMapper.insert(menu(parent.getId(), "操作日志", "operlog", "system/operlog/index", "system:operlog:list", 1, 9));
        log.info("操作日志菜单已播种");
    }

    private Long ensureAdminUser() {
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, ADMIN_USERNAME));
        if (existing != null) {
            return existing.getId();
        }
        SysUser user = new SysUser();
        user.setUsername(ADMIN_USERNAME);
        user.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
        user.setName("超级管理员");
        user.setPhone("");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        log.info("已创建默认管理员账号：{} / 初始密码：{}（请尽快修改）", ADMIN_USERNAME, ADMIN_PASSWORD);
        return user.getId();
    }

    private Long ensureAdminRole() {
        SysRole existing = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, ADMIN_ROLE_CODE));
        if (existing != null) {
            return existing.getId();
        }
        SysRole role = new SysRole();
        role.setRoleCode(ADMIN_ROLE_CODE);
        role.setRoleName("超级管理员");
        role.setRoleDesc("系统内置超级管理员，拥有全部权限");
        role.setCreateTime(LocalDateTime.now());
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.insert(role);
        return role.getId();
    }

    private void ensureUserRole(Long userId, Long roleId) {
        boolean exists = userRoleMapper.selectUserIdsByRoleId(roleId).stream()
                .anyMatch(id -> id.equals(userId));
        if (!exists) {
            userRoleMapper.insertRelation(userId, roleId);
        }
    }

    private List<Long> seedMenus() {
        List<Long> ids = new ArrayList<>();

        SysMenu system = menu(0L, "系统管理", "/system", "Layout", "", 1, 1);
        menuMapper.insert(system);
        ids.add(system.getId());

        SysMenu userMenu = menu(system.getId(), "用户管理", "user", "system/user/index", "system:user:list", 1, 1);
        menuMapper.insert(userMenu);
        ids.add(userMenu.getId());
        ids.add(button(userMenu.getId(), "新增", "system:user:add", 1));
        ids.add(button(userMenu.getId(), "编辑", "system:user:edit", 2));
        ids.add(button(userMenu.getId(), "删除", "system:user:delete", 3));
        ids.add(button(userMenu.getId(), "重置密码", "system:user:reset", 4));

        SysMenu roleMenu = menu(system.getId(), "角色管理", "role", "system/role/index", "system:role:list", 1, 2);
        menuMapper.insert(roleMenu);
        ids.add(roleMenu.getId());
        ids.add(button(roleMenu.getId(), "新增", "system:role:add", 1));
        ids.add(button(roleMenu.getId(), "编辑", "system:role:edit", 2));
        ids.add(button(roleMenu.getId(), "删除", "system:role:delete", 3));
        ids.add(button(roleMenu.getId(), "分配权限", "system:role:assign", 4));

        SysMenu menuMenu = menu(system.getId(), "菜单管理", "menu", "system/menu/index", "system:menu:list", 1, 3);
        menuMapper.insert(menuMenu);
        ids.add(menuMenu.getId());
        ids.add(button(menuMenu.getId(), "新增", "system:menu:add", 1));
        ids.add(button(menuMenu.getId(), "编辑", "system:menu:edit", 2));
        ids.add(button(menuMenu.getId(), "删除", "system:menu:delete", 3));

        return ids;
    }

    private SysMenu menu(Long parentId, String name, String path, String component,
                         String perms, Integer type, Integer sort) {
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuName(name);
        m.setPath(path);
        m.setComponent(component);
        m.setPerms(perms);
        m.setType(type);
        m.setSort(sort);
        m.setCreateTime(LocalDateTime.now());
        m.setUpdateTime(LocalDateTime.now());
        return m;
    }

    private Long button(Long parentId, String name, String perms, Integer sort) {
        SysMenu m = menu(parentId, name, "", "", perms, 2, sort);
        menuMapper.insert(m);
        return m.getId();
    }

    // ===================== 医院模块角色与菜单播种 =====================

    /**
     * 播种医院模块的固定岗位角色与菜单，并分配数据权限对应的功能权限。
     * <ul>
     *     <li>角色：院长/科室主任/医生/护士/护工（幂等，已存在则复用）</li>
     *     <li>菜单：医院管理分组下的 医院/科室/员工/出勤/统计 五个页面 + 「查看本人出勤」按钮权限</li>
     *     <li>权限分配：院长=全部；科室主任=员工/出勤/统计；医生护士护工=仅查看本人出勤</li>
     * </ul>
     * 菜单仅首次播种（以 hospital:stats:view 是否存在为哨兵）；角色权限分配仅在角色尚无菜单时执行，
     * 以尊重后续通过 UI 手动调整。
     */
    private void ensureHospitalModule() {
        Map<String, Long> roleIds = new HashMap<>();
        for (int i = 0; i < HOSPITAL_ROLE_CODES.length; i++) {
            roleIds.put(HOSPITAL_ROLE_CODES[i], ensureHospitalRole(HOSPITAL_ROLE_CODES[i], HOSPITAL_ROLE_NAMES[i]));
        }

        // 菜单已播种则跳过（幂等）
        if (menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getPerms, "hospital:stats:view")) > 0) {
            return;
        }

        SysMenu top = menu(0L, "医院管理", "hospital", "hospital/index", "", 1, 1);
        menuMapper.insert(top);
        SysMenu mHospital = menu(top.getId(), "医院管理", "info", "hospital/info/index", "hospital:manage", 1, 1);
        menuMapper.insert(mHospital);
        SysMenu mDept = menu(top.getId(), "科室管理", "dept", "hospital/dept/index", "hospital:manage", 1, 2);
        menuMapper.insert(mDept);
        SysMenu mEmp = menu(top.getId(), "员工管理", "employee", "hospital/employee/index", "hospital:employee:list", 1, 3);
        menuMapper.insert(mEmp);
        SysMenu mAtt = menu(top.getId(), "出勤管理", "attendance", "hospital/attendance/index", "hospital:attendance:list", 1, 4);
        menuMapper.insert(mAtt);
        SysMenu mStats = menu(top.getId(), "出勤统计", "stats", "hospital/stats/index", "hospital:stats:view", 1, 5);
        menuMapper.insert(mStats);
        // 普通员工「查看本人出勤」按钮权限（供路由守卫与控制器鉴权，非导航菜单）
        Long bSelf = button(mAtt.getId(), "查看本人出勤", "hospital:attendance:self", 1);
        // 员工维护（增改删）按钮权限：院长/科室主任可管理本科室员工
        Long bEmpEdit = button(mEmp.getId(), "维护员工", "hospital:employee:edit", 2);
        // 出勤维护（增改删）按钮权限：院长/科室主任可管理本科室出勤
        Long bAttEdit = button(mAtt.getId(), "维护出勤", "hospital:attendance:edit", 2);

        // 权限分配（仅当角色尚无菜单时，避免覆盖 UI 后续调整）
        assignIfFresh(roleIds.get(RoleConstants.HOSPITAL_DIRECTOR),
                List.of(mHospital.getId(), mDept.getId(), mEmp.getId(), mAtt.getId(), mStats.getId(), bEmpEdit, bAttEdit));
        assignIfFresh(roleIds.get(RoleConstants.DEPT_DIRECTOR),
                List.of(mEmp.getId(), mAtt.getId(), mStats.getId(), bEmpEdit, bAttEdit));
        for (String code : new String[]{RoleConstants.DOCTOR, RoleConstants.NURSE, RoleConstants.CAREGIVER}) {
            assignIfFresh(roleIds.get(code), List.of(bSelf));
        }
        log.info("医院模块角色与菜单已播种");
    }

    // ===================== 门诊挂号取号模块角色与菜单播种 =====================

    /**
     * 播种门诊挂号模块的固定角色与菜单，并分配对应功能权限。
     * <ul>
     *     <li>角色：clinic_admin(门诊管理员) / cashier(收费窗口操作员) / self_service_kiosk(自助机终端)；
     *         医生复用医院模块的 doctor 角色（看本人患者）。</li>
     *     <li>菜单：门诊挂号分组下的 排班与号源 / 挂号记录 / 挂号统计 三个页面 + 现场挂号/取号/退号/标记就诊/查看患者 按钮权限。</li>
     *     <li>权限分配仅在角色尚无菜单时执行，以尊重后续 UI 手动调整。</li>
     * </ul>
     * 菜单仅首次播种（以 registration:stats:view 是否存在为哨兵）。
     */
    private void ensureRegistrationModule() {
        String[] codes = {RoleConstants.CLINIC_ADMIN, RoleConstants.CASHIER,
                RoleConstants.SELF_SERVICE_KIOSK};
        String[] names = {"门诊管理员", "收费窗口操作员", "自助机终端"};
        Map<String, Long> roleIds = new HashMap<>();
        for (int i = 0; i < codes.length; i++) {
            roleIds.put(codes[i], ensureHospitalRole(codes[i], names[i]));
        }
        Long doctorId = ensureHospitalRole(RoleConstants.DOCTOR, "医生");

        // 菜单已播种则跳过（幂等，以 registration:stats:view 为哨兵）
        if (menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getPerms, "registration:stats:view")) > 0) {
            return;
        }

        SysMenu top = menu(0L, "门诊挂号", "registration", "registration/index", "", 1, 2);
        menuMapper.insert(top);
        SysMenu mSchedule = menu(top.getId(), "排班与号源", "schedule", "registration/schedule/index", "registration:schedule:manage", 1, 1);
        menuMapper.insert(mSchedule);
        SysMenu mRecord = menu(top.getId(), "挂号记录", "record", "registration/record/index", "registration:register:list", 1, 2);
        menuMapper.insert(mRecord);
        SysMenu mStats = menu(top.getId(), "挂号统计", "stats", "registration/stats/index", "registration:stats:view", 1, 3);
        menuMapper.insert(mStats);

        Long bCreate = button(mRecord.getId(), "现场挂号", "registration:register:create", 1);
        Long bTake = button(mRecord.getId(), "取号", "registration:register:take", 2);
        Long bRefund = button(mRecord.getId(), "退号/取消", "registration:register:refund", 3);
        Long bTreat = button(mRecord.getId(), "标记就诊", "registration:register:treat", 4);
        Long bPatient = button(mRecord.getId(), "查看患者", "registration:patient:list", 5);

        assignIfFresh(roleIds.get(RoleConstants.CLINIC_ADMIN),
                List.of(mSchedule.getId(), mRecord.getId(), mStats.getId(), bCreate, bTake, bRefund, bTreat, bPatient));
        assignIfFresh(roleIds.get(RoleConstants.CASHIER),
                List.of(mRecord.getId(), bCreate, bTake, bRefund));
        assignIfFresh(roleIds.get(RoleConstants.SELF_SERVICE_KIOSK),
                List.of(mRecord.getId(), bTake));
        assignIfFresh(doctorId,
                List.of(mRecord.getId(), bTreat, bPatient));
        log.info("门诊挂号模块角色与菜单已播种");
    }

    private Long ensureHospitalRole(String code, String name) {
        SysRole existing = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, code));
        if (existing != null) {
            return existing.getId();
        }
        SysRole role = new SysRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setRoleDesc("医院岗位角色（自带数据权限规则）");
        role.setCreateTime(LocalDateTime.now());
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.insert(role);
        return role.getId();
    }

    private void assignIfFresh(Long roleId, List<Long> menuIds) {
        if (roleId == null || menuIds == null || menuIds.isEmpty()) {
            return;
        }
        Set<Long> linked = roleMenuMapper.listByRoleId(roleId).stream()
                .map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
        if (!linked.isEmpty()) {
            return; // 已有菜单权限，尊重 UI 调整，不覆盖
        }
        for (Long menuId : menuIds) {
            roleMenuMapper.insertRelation(roleId, menuId);
        }
    }
}
