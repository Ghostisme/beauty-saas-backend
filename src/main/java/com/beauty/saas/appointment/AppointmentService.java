package com.beauty.saas.appointment;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.AppointmentRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

/** Tenant-scoped appointment calendar persistence. */
@Service
@RequiredArgsConstructor
public class AppointmentService {
    private static final Set<String> STATUSES = Set.of("PENDING", "CONFIRMED", "ARRIVED", "DONE", "CANCELLED", "TEMP_BLOCK");
    private final IamRepository repo;
    private final AccessService access;

    private AccountPrincipal actor(String permission) { var actor = access.currentTenant(); actor.require(permission); return actor; }
    private AccountPrincipal write() {
        var actor = actor("appointments:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", actor.tenantId()) == null)
            throw new ApiException(409, "企业已停用，请先由平台启用");
        var fresh = access.reload(actor); fresh.require("appointments:write"); return fresh;
    }
    private static void page(int page, int size) { if (page < 1 || page > 100000 || size < 1 || size > 100) throw new ApiException(400, "分页参数不正确，每页最多 100 条"); }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    public Page<Map<String,Object>> list(AppointmentQuery query) {
        var actor = actor("appointments:read"); page(query.page(), query.pageSize());
        var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE a.tenant_id=?");
        if (query.date() != null) { where.append(" AND a.appointment_date=?"); args.add(query.date()); }
        if (query.departmentId() != null) {
            actor.requireDepartment("appointments:read", query.departmentId());
            where.append(" AND a.department_id=?"); args.add(query.departmentId());
        } else if (!actor.global("appointments:read")) {
            var allowed = actor.scopes().getOrDefault("appointments:read", Set.of()).stream().filter(id -> id > 0).toList();
            if (allowed.isEmpty()) where.append(" AND 1=0");
            else { where.append(" AND a.department_id IN (").append(marks(allowed.size())).append(")"); args.addAll(allowed); }
        }
        if (query.status() != null && !query.status().isBlank()) {
            if (!STATUSES.contains(query.status())) throw new ApiException(400, "预约状态不正确");
            where.append(" AND a.status=?"); args.add(query.status());
        }
        var total = repo.count("SELECT COUNT(*) FROM biz_appointment a" + where, args.toArray());
        args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT a.*,d.name department_name FROM biz_appointment a JOIN sys_department d ON d.tenant_id=a.tenant_id AND d.id=a.department_id" + where + " ORDER BY a.start_time,a.id LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    @Transactional public long save(Long id, AppointmentSave input) {
        var actor = write();
        actor.requireDepartment("appointments:write", input.departmentId());
        var department = repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), input.departmentId());
        if (department == null) throw new ApiException(404, "门店不存在或已停用");
        if (id == null) return repo.insert("INSERT INTO biz_appointment(tenant_id,department_id,appointment_date,start_time,duration_minutes,customer_name,phone,service_name,staff_name,room_name,status,color,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), input.departmentId(), input.appointmentDate(), input.startTime(), input.durationMinutes(), input.customerName().trim(), text(input.phone()), input.serviceName().trim(), text(input.staffName()), text(input.roomName()), input.status(), input.color(), text(input.note()));
        var existing = repo.one("SELECT id FROM biz_appointment WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenantId(), id);
        if (existing == null) throw new ApiException(404, "预约不存在");
        repo.update("UPDATE biz_appointment SET department_id=?,appointment_date=?,start_time=?,duration_minutes=?,customer_name=?,phone=?,service_name=?,staff_name=?,room_name=?,status=?,color=?,note=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
            input.departmentId(), input.appointmentDate(), input.startTime(), input.durationMinutes(), input.customerName().trim(), text(input.phone()), input.serviceName().trim(), text(input.staffName()), text(input.roomName()), input.status(), input.color(), text(input.note()), actor.tenantId(), id);
        return id;
    }

    @Transactional public void delete(long id) {
        var actor = write();
        var row = repo.one("SELECT department_id FROM biz_appointment WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenantId(), id);
        if (row == null) throw new ApiException(404, "预约不存在");
        actor.requireDepartment("appointments:write", id(row, "departmentId"));
        if (repo.update("DELETE FROM biz_appointment WHERE tenant_id=? AND id=?", actor.tenantId(), id) == 0) throw new ApiException(404, "预约不存在");
    }
}
