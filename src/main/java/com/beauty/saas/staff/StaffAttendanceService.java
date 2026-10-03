package com.beauty.saas.staff;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.StaffAttendanceRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalTime;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class StaffAttendanceService {
    private static final Set<String> TYPES = Set.of("FIXED", "SCHEDULE");
    private static final Set<String> OVERTIME_MODES = Set.of("AFTER_END", "AT_TIME");
    private static final Set<String> PUNCH_METHODS = Set.of("LOCATION", "WIFI", "CODE");

    private final IamRepository repo;
    private final AccessService access;
    private final ObjectMapper json;

    private AccountPrincipal read() {
        var actor = access.currentTenant();
        actor.require("users:read");
        return actor;
    }

    private AccountPrincipal write() {
        var actor = access.currentTenant();
        actor.requireGlobal("users:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", actor.tenantId()) == null)
            throw new ApiException(409, "企业已停用");
        access.reload(actor).requireGlobal("users:write");
        return actor;
    }

    private void store(AccountPrincipal actor, long storeId, String permission) {
        actor.requireDepartment(permission, storeId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), storeId) == null)
            throw new ApiException(404, "门店不存在或已停用");
    }

    public List<Map<String,Object>> stores() {
        var actor = read();
        return repo.rows("SELECT id,name FROM sys_department WHERE tenant_id=? AND type='STORE' AND status=1 ORDER BY sort_order,id", actor.tenantId())
            .stream().filter(row -> actor.can("users:read", id(row, "id"))).toList();
    }

    public List<Map<String,Object>> staff() {
        var actor = read();
        return repo.rows("SELECT u.id,u.nickname,u.phone,u.position_id,p.name position_name " +
            "FROM sys_user u LEFT JOIN biz_staff_position p ON p.tenant_id=u.tenant_id AND p.id=u.position_id " +
            "WHERE u.tenant_id=? AND u.deleted=0 AND u.status=1 ORDER BY u.nickname,u.id", actor.tenantId())
            .stream().filter(row -> visibleStaff(actor.tenantId(), id(row, "id"), actor)).toList();
    }

    private boolean visibleStaff(long tenant, long userId, AccountPrincipal actor) {
        if (actor.global("users:read")) return true;
        return repo.count("SELECT COUNT(*) FROM sys_user_department ud WHERE ud.tenant_id=? AND ud.user_id=? AND " +
            "EXISTS(SELECT 1 FROM sys_department d WHERE d.tenant_id=ud.tenant_id AND d.id=ud.department_id AND d.type='STORE' AND d.status=1) AND " +
            "ud.department_id IN (SELECT id FROM sys_department WHERE tenant_id=? AND type='STORE' AND status=1)", tenant, userId, tenant) > 0;
    }

    public List<Map<String,Object>> rules() {
        var actor = read();
        var result = new ArrayList<Map<String,Object>>();
        for (var row : repo.rows("SELECT r.id,r.name,r.attendance_type,r.all_stores,r.all_employees,r.rule_json,r.update_time,u.nickname updater_name " +
            "FROM biz_staff_attendance_rule r LEFT JOIN sys_user u ON u.tenant_id=r.tenant_id AND u.id=r.updater_user_id " +
            "WHERE r.tenant_id=? AND r.deleted=0 ORDER BY r.id DESC", actor.tenantId())) {
            var stores = storesFor(actor.tenantId(), id(row, "id"));
            if (((Number) row.get("allStores")).intValue() != 1 && stores.stream().noneMatch(store -> actor.can("users:read", id(store, "id")))) continue;
            var rule = decode(text(row, "ruleJson"));
            var value = toView(row, rule, stores, actor.tenantId());
            result.add(value);
        }
        return result;
    }

    private Map<String,Object> toView(Map<String,Object> row, RuleSave rule, List<Map<String,Object>> storeRows, long tenant) {
        var value = new LinkedHashMap<String,Object>();
        value.put("id", id(row, "id"));
        value.put("name", text(row, "name"));
        value.put("attendanceType", rule.attendanceType());
        value.put("allStores", rule.allStores());
        value.put("storeIds", storeRows.stream().map(store -> id(store, "id")).toList());
        value.put("storeNames", storeRows.stream().map(store -> text(store, "name")).toList());
        value.put("allEmployees", rule.allEmployees());
        value.put("userIds", rule.userIds());
        value.put("userNames", userNames(tenant, rule.userIds()));
        value.put("workdays", rule.workdays());
        value.put("overtimeEnabled", rule.overtimeEnabled());
        value.put("overtimeMode", rule.overtimeMode());
        value.put("overtimeMinutes", rule.overtimeMinutes());
        value.put("overtimeStartTime", rule.overtimeStartTime());
        value.put("overtimeNonworkday", rule.overtimeNonworkday());
        value.put("punchMethod", rule.punchMethod());
        value.put("radiusMeters", rule.radiusMeters());
        value.put("locations", rule.locations());
        value.put("wifis", rule.wifis());
        value.put("updateTime", row.get("updateTime"));
        value.put("updaterName", row.get("updaterName"));
        return value;
    }

    private List<Map<String,Object>> storesFor(long tenant, long ruleId) {
        return repo.rows("SELECT d.id,d.name FROM biz_staff_attendance_rule_store rs JOIN sys_department d ON d.tenant_id=rs.tenant_id AND d.id=rs.department_id " +
            "WHERE rs.tenant_id=? AND rs.rule_id=? AND d.status=1 ORDER BY d.sort_order,d.id", tenant, ruleId);
    }

    private Map<Long,String> userNames(long tenant, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        var args = new ArrayList<Object>(); args.add(tenant); args.addAll(ids);
        var result = new LinkedHashMap<Long,String>();
        repo.rows("SELECT id,nickname FROM sys_user WHERE tenant_id=? AND id IN (" + marks + ")", args.toArray())
            .forEach(row -> result.put(id(row, "id"), text(row, "nickname")));
        return result;
    }

    private RuleSave decode(String value) {
        try { return json.readValue(value, RuleSave.class); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("考勤规则数据损坏", cause); }
    }

    private String encode(RuleSave value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("考勤规则无法保存", cause); }
    }

    private void validate(AccountPrincipal actor, RuleSave input) {
        if (!TYPES.contains(input.attendanceType())) throw new ApiException(400, "考勤类型不正确");
        if (!OVERTIME_MODES.contains(input.overtimeMode())) throw new ApiException(400, "加班规则不正确");
        if (!PUNCH_METHODS.contains(input.punchMethod())) throw new ApiException(400, "打卡方式不正确");
        if (input.allStores() && !input.storeIds().isEmpty()) {
            // The client may submit the checked store list together with“所有门店”；normalize it below.
        }
        var stores = new LinkedHashSet<>(input.storeIds());
        if (!input.allStores() && stores.isEmpty()) throw new ApiException(400, "请选择适用门店");
        if (stores.size() != input.storeIds().size()) throw new ApiException(400, "适用门店不能重复");
        for (long storeId : stores) store(actor, storeId, "users:write");
        if (!input.allEmployees() && input.userIds().isEmpty()) throw new ApiException(400, "请选择员工");
        if (new LinkedHashSet<>(input.userIds()).size() != input.userIds().size()) throw new ApiException(400, "员工不能重复");
        for (long userId : input.userIds()) requireStaff(actor.tenantId(), userId);
        if (input.workdays().stream().map(Workday::weekday).distinct().count() != 7) throw new ApiException(400, "工作日设置必须包含周一至周日");
        for (var day : input.workdays()) if (day.enabled() && !day.start().isBefore(day.end())) throw new ApiException(400, "打卡开始时间必须早于结束时间");
        if (input.overtimeEnabled()) {
            if (input.overtimeMode().equals("AFTER_END") && (input.overtimeMinutes() == null || input.overtimeMinutes() < 0)) throw new ApiException(400, "请输入下班后加班分钟数");
            if (input.overtimeMode().equals("AT_TIME") && input.overtimeStartTime() == null) throw new ApiException(400, "请设置加班时间点");
        }
        if (input.punchMethod().equals("WIFI") && input.wifis().isEmpty()) throw new ApiException(400, "WIFI列表不能为空");
        var macs = new HashSet<String>();
        for (var wifi : input.wifis()) if (!macs.add(wifi.macAddress().trim().toUpperCase(Locale.ROOT))) throw new ApiException(400, "WIFI地址不能重复");
    }

    private void requireStaff(long tenant, long userId) {
        if (repo.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND deleted=0 AND status=1", tenant, userId) == null)
            throw new ApiException(404, "员工不存在或已停用");
    }

    public List<Map<String,Object>> conflicts(RuleSave input, Long excludeId) {
        var actor = read();
        validate(actor, input);
        var result = new ArrayList<Map<String,Object>>();
        for (var row : repo.rows("SELECT id,name,rule_json FROM biz_staff_attendance_rule WHERE tenant_id=? AND deleted=0 " +
            (excludeId == null ? "" : "AND id<>?") + " ORDER BY id DESC", excludeId == null ? new Object[]{actor.tenantId()} : new Object[]{actor.tenantId(), excludeId})) {
            long ruleId = id(row, "id");
            var existing = decode(text(row, "ruleJson"));
            if (!overlapStores(input, existing, actor.tenantId(), ruleId) || !overlapEmployees(input, existing)) continue;
            var conflict = new LinkedHashMap<String,Object>();
            conflict.put("ruleId", ruleId);
            conflict.put("originalRule", text(row, "name"));
            conflict.put("storeNames", storesFor(actor.tenantId(), ruleId).stream().map(store -> text(store, "name")).toList());
            conflict.put("employeeName", input.allEmployees() || existing.allEmployees() ? "适用全员工" : overlapUserNames(actor.tenantId(), input.userIds(), existing.userIds()));
            result.add(conflict);
        }
        return result;
    }

    private boolean overlapStores(RuleSave current, RuleSave existing, long tenant, long existingId) {
        if (current.allStores() || existing.allStores()) return true;
        var storeIds = new HashSet<>(current.storeIds());
        return storesFor(tenant, existingId).stream().map(row -> id(row, "id")).anyMatch(storeIds::contains);
    }

    private boolean overlapEmployees(RuleSave current, RuleSave existing) {
        if (current.allEmployees() || existing.allEmployees()) return true;
        return current.userIds().stream().anyMatch(new HashSet<>(existing.userIds())::contains);
    }

    private String overlapUserNames(long tenant, List<Long> first, List<Long> second) {
        var ids = new HashSet<>(second); ids.retainAll(first);
        return ids.stream().map(id -> userNames(tenant, List.of(id)).get(id)).filter(Objects::nonNull).sorted().reduce((a,b) -> a + "、" + b).orElse("指定员工");
    }

    @Transactional public long save(Long recordId, RuleSave input) {
        var actor = write();
        long tenant = actor.tenantId();
        if (recordId != null && repo.one("SELECT id FROM biz_staff_attendance_rule WHERE tenant_id=? AND id=? AND deleted=0", tenant, recordId) == null)
            throw new ApiException(404, "考勤规则不存在");
        validate(actor, input);
        if (!input.force() && !conflictsForWrite(actor, input, recordId).isEmpty()) throw new ApiException(409, "存在考勤方案冲突，请确认后保存");
        String name = input.name().trim();
        if (repo.count("SELECT COUNT(*) FROM biz_staff_attendance_rule WHERE tenant_id=? AND name=? AND deleted=0 AND id<>?", tenant, name, recordId == null ? 0 : recordId) > 0)
            throw new ApiException(409, "规则名称已存在");
        long ruleId;
        if (recordId == null) ruleId = repo.insert("INSERT INTO biz_staff_attendance_rule(tenant_id,name,attendance_type,all_stores,all_employees,rule_json,updater_user_id) VALUES(?,?,?,?,?,?,?)",
            tenant, name, input.attendanceType(), input.allStores() ? 1 : 0, input.allEmployees() ? 1 : 0, encode(input), actor.userId());
        else {
            ruleId = recordId;
            repo.update("UPDATE biz_staff_attendance_rule SET name=?,attendance_type=?,all_stores=?,all_employees=?,rule_json=?,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                name, input.attendanceType(), input.allStores() ? 1 : 0, input.allEmployees() ? 1 : 0, encode(input), actor.userId(), tenant, ruleId);
            repo.update("DELETE FROM biz_staff_attendance_rule_store WHERE tenant_id=? AND rule_id=?", tenant, ruleId);
            repo.update("DELETE FROM biz_staff_attendance_rule_user WHERE tenant_id=? AND rule_id=?", tenant, ruleId);
        }
        for (long storeId : new LinkedHashSet<>(input.storeIds())) repo.update("INSERT INTO biz_staff_attendance_rule_store(tenant_id,rule_id,department_id) VALUES(?,?,?)", tenant, ruleId, storeId);
        for (long userId : new LinkedHashSet<>(input.userIds())) repo.update("INSERT INTO biz_staff_attendance_rule_user(tenant_id,rule_id,user_id) VALUES(?,?,?)", tenant, ruleId, userId);
        return ruleId;
    }

    private List<Map<String,Object>> conflictsForWrite(AccountPrincipal actor, RuleSave input, Long recordId) {
        // conflicts() deliberately checks read permission. This helper is identical but keeps a write transaction's actor.
        var result = new ArrayList<Map<String,Object>>();
        for (var row : repo.rows("SELECT id,rule_json FROM biz_staff_attendance_rule WHERE tenant_id=? AND deleted=0 " +
            (recordId == null ? "" : "AND id<>?"), recordId == null ? new Object[]{actor.tenantId()} : new Object[]{actor.tenantId(), recordId})) {
            var existing = decode(text(row, "ruleJson"));
            if (overlapStores(input, existing, actor.tenantId(), id(row, "id")) && overlapEmployees(input, existing)) result.add(row);
        }
        return result;
    }

    @Transactional public void delete(long id) {
        var actor = write();
        if (repo.update("UPDATE biz_staff_attendance_rule SET deleted=1,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND deleted=0", actor.userId(), actor.tenantId(), id) == 0)
            throw new ApiException(404, "考勤规则不存在");
    }
}
