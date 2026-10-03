package com.beauty.saas.staff;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.StaffSchedulingRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class StaffSchedulingService {
    private final IamRepository repo;
    private final AccessService access;

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
        var fresh = access.reload(actor);
        fresh.requireGlobal("users:write");
        return fresh;
    }

    private void store(AccountPrincipal actor, long storeId, String permission) {
        actor.requireDepartment(permission, storeId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), storeId) == null)
            throw new ApiException(404, "门店不存在或已停用");
    }

    public List<Map<String,Object>> stores() {
        var actor = read();
        var rows = repo.rows("SELECT id,name FROM sys_department WHERE tenant_id=? AND type='STORE' AND status=1 ORDER BY sort_order,id", actor.tenantId());
        return rows.stream().filter(row -> actor.can("users:read", id(row,"id"))).toList();
    }

    public List<Map<String,Object>> staff(long storeId) {
        var actor = read();
        store(actor,storeId,"users:read");
        return repo.rows("SELECT u.id,u.nickname,u.phone,u.position_id,p.name position_name," +
            "CASE WHEN sp.user_id IS NULL THEN 0 ELSE 1 END participating " +
            "FROM sys_user u LEFT JOIN biz_staff_position p ON p.tenant_id=u.tenant_id AND p.id=u.position_id " +
            "LEFT JOIN biz_staff_schedule_participant sp ON sp.tenant_id=u.tenant_id AND sp.user_id=u.id AND sp.department_id=? " +
            "WHERE u.tenant_id=? AND u.deleted=0 AND u.status=1 AND " +
            "(EXISTS(SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.department_id=?) " +
            "OR EXISTS(SELECT 1 FROM sys_tenant_admin ta WHERE ta.tenant_id=u.tenant_id AND ta.user_id=u.id)) " +
            "ORDER BY u.nickname,u.id",storeId,actor.tenantId(),storeId);
    }

    public List<Map<String,Object>> shifts() {
        var actor = read();
        var result = new ArrayList<Map<String,Object>>();
        for (var shift : repo.rows("SELECT id,name,color FROM biz_staff_shift WHERE tenant_id=? ORDER BY id DESC",actor.tenantId())) {
            long shiftId = id(shift,"id");
            var assignedStores = repo.rows("SELECT d.id,d.name FROM biz_staff_shift_store ss JOIN sys_department d ON d.tenant_id=ss.tenant_id AND d.id=ss.department_id WHERE ss.tenant_id=? AND ss.shift_id=? AND d.status=1 ORDER BY d.id",actor.tenantId(),shiftId)
                .stream().filter(row -> actor.can("users:read",id(row,"id"))).toList();
            if (assignedStores.isEmpty()) continue;
            shift.put("storeIds",assignedStores.stream().map(row -> id(row,"id")).toList());
            shift.put("storeNames",assignedStores.stream().map(row -> text(row,"name")).toList());
            shift.put("periods",periods(actor.tenantId(),shiftId));
            result.add(shift);
        }
        return result;
    }

    private List<Map<String,String>> periods(long tenant, long shiftId) {
        return repo.rows("SELECT start_time,end_time FROM biz_staff_shift_period WHERE tenant_id=? AND shift_id=? ORDER BY sort_order",tenant,shiftId)
            .stream().map(row -> Map.of("start",time(row.get("startTime")),"end",time(row.get("endTime")))).toList();
    }

    private String time(Object value) { return value.toString().substring(0,5); }

    @Transactional public long saveShift(Long recordId, ShiftSave input) {
        var actor = write();
        long tenant = actor.tenantId();
        if (recordId != null && repo.one("SELECT id FROM biz_staff_shift WHERE tenant_id=? AND id=?",tenant,recordId) == null)
            throw new ApiException(404,"班次不存在");
        String name = input.name().trim();
        if (name.isBlank()) throw new ApiException(400,"请输入班次名称");
        if (repo.count("SELECT COUNT(*) FROM biz_staff_shift WHERE tenant_id=? AND name=? AND id<>?",tenant,name,recordId == null ? 0 : recordId) > 0)
            throw new ApiException(409,"班次名称已存在");
        var stores = new LinkedHashSet<>(input.storeIds());
        if (stores.size()!=input.storeIds().size()) throw new ApiException(400,"适用门店不能重复");
        stores.forEach(storeId -> store(actor,storeId,"users:write"));
        var sorted = new ArrayList<>(input.periods());
        sorted.sort(Comparator.comparing(Period::start));
        LocalTime previousEnd = null;
        for (var period : sorted) {
            if (!period.start().isBefore(period.end()) || previousEnd != null && period.start().isBefore(previousEnd))
                throw new ApiException(400,"工作时段必须按时间顺序且不能重叠");
            previousEnd = period.end();
        }
        if (recordId != null) {
            var usedStores = repo.rows("SELECT DISTINCT department_id FROM biz_staff_assignment WHERE tenant_id=? AND shift_id=?",tenant,recordId);
            if (usedStores.stream().anyMatch(row -> !stores.contains(id(row,"departmentId"))))
                throw new ApiException(409,"班次已用于门店排班，不能移除该门店");
        }
        long shiftId;
        if (recordId == null) shiftId = repo.insert("INSERT INTO biz_staff_shift(tenant_id,name,color) VALUES(?,?,?)",tenant,name,input.color().toUpperCase(Locale.ROOT));
        else {
            shiftId = recordId;
            repo.update("UPDATE biz_staff_shift SET name=?,color=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",name,input.color().toUpperCase(Locale.ROOT),tenant,shiftId);
            repo.update("DELETE FROM biz_staff_shift_store WHERE tenant_id=? AND shift_id=?",tenant,shiftId);
            repo.update("DELETE FROM biz_staff_shift_period WHERE tenant_id=? AND shift_id=?",tenant,shiftId);
        }
        for (long storeId : stores) repo.update("INSERT INTO biz_staff_shift_store(tenant_id,shift_id,department_id) VALUES(?,?,?)",tenant,shiftId,storeId);
        for (int i=0; i<sorted.size(); i++) {
            var period = sorted.get(i);
            repo.update("INSERT INTO biz_staff_shift_period(tenant_id,shift_id,sort_order,start_time,end_time) VALUES(?,?,?,?,?)",tenant,shiftId,i,Time.valueOf(period.start()),Time.valueOf(period.end()));
        }
        return shiftId;
    }

    @Transactional public void deleteShift(long shiftId) {
        var actor = write();
        long tenant = actor.tenantId();
        if (repo.one("SELECT id FROM biz_staff_shift WHERE tenant_id=? AND id=?",tenant,shiftId) == null)
            throw new ApiException(404,"班次不存在");
        if (repo.count("SELECT COUNT(*) FROM biz_staff_assignment WHERE tenant_id=? AND shift_id=?",tenant,shiftId)>0)
            throw new ApiException(409,"班次已有排班记录，不能删除");
        repo.update("DELETE FROM biz_staff_shift_period WHERE tenant_id=? AND shift_id=?",tenant,shiftId);
        repo.update("DELETE FROM biz_staff_shift_store WHERE tenant_id=? AND shift_id=?",tenant,shiftId);
        repo.update("DELETE FROM biz_staff_shift WHERE tenant_id=? AND id=?",tenant,shiftId);
    }

    @Transactional public void saveParticipants(ParticipantsSave input) {
        var actor = write();
        long tenant = actor.tenantId(), storeId = input.storeId();
        store(actor,storeId,"users:write");
        var users = new LinkedHashSet<>(input.userIds());
        if (users.size()!=input.userIds().size()) throw new ApiException(400,"员工不能重复");
        for (long userId : users) requireStaff(tenant,storeId,userId);
        repo.update("DELETE FROM biz_staff_schedule_participant WHERE tenant_id=? AND department_id=?",tenant,storeId);
        for (long userId : users) repo.update("INSERT INTO biz_staff_schedule_participant(tenant_id,department_id,user_id) VALUES(?,?,?)",tenant,storeId,userId);
    }

    private void requireStaff(long tenant,long storeId,long userId) {
        if (repo.one("SELECT id FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND u.deleted=0 AND u.status=1 AND " +
            "(EXISTS(SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.department_id=?) " +
            "OR EXISTS(SELECT 1 FROM sys_tenant_admin ta WHERE ta.tenant_id=u.tenant_id AND ta.user_id=u.id))",tenant,userId,storeId)==null)
            throw new ApiException(404,"员工不在该门店或已停用");
    }

    public Map<String,Object> calendar(long storeId,LocalDate startDate,LocalDate endDate) {
        var actor = read();
        store(actor,storeId,"users:read");
        if (startDate.isAfter(endDate) || ChronoUnit.DAYS.between(startDate,endDate)>41)
            throw new ApiException(400,"排班日期范围不能超过 42 天");
        var assignments = repo.rows("SELECT a.user_id,a.work_date,a.shift_id,s.name shift_name,s.color FROM biz_staff_assignment a JOIN biz_staff_shift s ON s.tenant_id=a.tenant_id AND s.id=a.shift_id WHERE a.tenant_id=? AND a.department_id=? AND a.work_date BETWEEN ? AND ? ORDER BY a.work_date,a.user_id",actor.tenantId(),storeId,startDate,endDate);
        var available = staff(storeId);
        var assignedIds = assignments.stream().map(row -> id(row,"userId")).collect(java.util.stream.Collectors.toSet());
        var visible = available.stream().filter(row -> ((Number) row.get("participating")).intValue()==1 || assignedIds.contains(id(row,"id"))).toList();
        return Map.of("staff",visible,"assignments",assignments);
    }

    @Transactional public void saveAssignment(AssignmentSave input) {
        var actor = write();
        long tenant = actor.tenantId(), storeId = input.storeId(), userId = input.userId();
        store(actor,storeId,"users:write");
        requireStaff(tenant,storeId,userId);
        if (input.shiftId()!=null) {
            if (repo.one("SELECT shift_id FROM biz_staff_shift_store WHERE tenant_id=? AND department_id=? AND shift_id=?",tenant,storeId,input.shiftId())==null)
                throw new ApiException(404,"班次不适用于该门店");
            if (repo.one("SELECT user_id FROM biz_staff_schedule_participant WHERE tenant_id=? AND department_id=? AND user_id=?",tenant,storeId,userId)==null)
                throw new ApiException(409,"员工尚未参与该门店排班");
        }
        var existing = repo.one("SELECT id,department_id FROM biz_staff_assignment WHERE tenant_id=? AND user_id=? AND work_date=?",tenant,userId,input.date());
        if (existing!=null && id(existing,"departmentId")!=storeId) throw new ApiException(409,"员工当天已在其他门店排班");
        if (input.shiftId()==null) {
            if (existing!=null) repo.update("DELETE FROM biz_staff_assignment WHERE tenant_id=? AND id=?",tenant,id(existing,"id"));
        } else if (existing==null) {
            repo.insert("INSERT INTO biz_staff_assignment(tenant_id,department_id,user_id,work_date,shift_id) VALUES(?,?,?,?,?)",tenant,storeId,userId,input.date(),input.shiftId());
        } else {
            repo.update("UPDATE biz_staff_assignment SET shift_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",input.shiftId(),tenant,id(existing,"id"));
        }
    }

    @Transactional public int copyPreviousWeek(CopyWeek input) {
        var actor = write();
        long tenant = actor.tenantId(), storeId = input.storeId();
        store(actor,storeId,"users:write");
        if (input.startDate().getDayOfWeek()!=DayOfWeek.MONDAY) throw new ApiException(400,"请选择周一作为本周起始日");
        LocalDate previous = input.startDate().minusWeeks(1);
        var rows = repo.rows("SELECT user_id,work_date,shift_id FROM biz_staff_assignment WHERE tenant_id=? AND department_id=? AND work_date BETWEEN ? AND ?",tenant,storeId,previous,previous.plusDays(6));
        int copied=0;
        for (var row : rows) {
            long userId=id(row,"userId"), shiftId=id(row,"shiftId");
            LocalDate source=((java.sql.Date) row.get("workDate")).toLocalDate();
            LocalDate target=source.plusWeeks(1);
            if (repo.count("SELECT COUNT(*) FROM biz_staff_assignment WHERE tenant_id=? AND user_id=? AND work_date=?",tenant,userId,target)>0) continue;
            if (repo.count("SELECT COUNT(*) FROM biz_staff_schedule_participant WHERE tenant_id=? AND department_id=? AND user_id=?",tenant,storeId,userId)==0) continue;
            if (repo.count("SELECT COUNT(*) FROM biz_staff_shift_store WHERE tenant_id=? AND department_id=? AND shift_id=?",tenant,storeId,shiftId)==0) continue;
            repo.insert("INSERT INTO biz_staff_assignment(tenant_id,department_id,user_id,work_date,shift_id) VALUES(?,?,?,?,?)",tenant,storeId,userId,target,shiftId);
            copied++;
        }
        return copied;
    }
}
