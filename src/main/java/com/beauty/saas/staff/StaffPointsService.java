package com.beauty.saas.staff;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.StaffPointsRequests.PointSave;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import com.beauty.saas.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.beauty.saas.iam.IamRepository.id;
import static com.beauty.saas.iam.IamRepository.text;

@Service
@RequiredArgsConstructor
public class StaffPointsService {
    private static final Set<String> POINT_TYPES = Set.of("ADD", "DEDUCT");
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED");

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
        access.reload(actor).requireGlobal("users:write");
        return actor;
    }

    public List<Map<String, Object>> stores() {
        var actor = read();
        return repo.rows("SELECT id,name FROM sys_department WHERE tenant_id=? AND type='STORE' AND status=1 ORDER BY sort_order,id", actor.tenantId())
            .stream().filter(row -> actor.can("users:read", id(row, "id"))).toList();
    }

    public List<Map<String, Object>> staff() {
        var actor = read();
        var args = new ArrayList<Object>();
        args.add(actor.tenantId());
        var scope = "";
        if (!actor.global("users:read")) {
            var visibleStores = visibleStoreIds(actor);
            if (visibleStores.isEmpty()) return List.of();
            scope = " AND EXISTS (SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.department_id IN (" +
                String.join(",", Collections.nCopies(visibleStores.size(), "?")) + "))";
            args.addAll(visibleStores);
        }
        return repo.rows("SELECT DISTINCT u.id,u.nickname,u.phone,u.position_id,p.name position_name " +
                "FROM sys_user u LEFT JOIN biz_staff_position p ON p.tenant_id=u.tenant_id AND p.id=u.position_id " +
                "WHERE u.tenant_id=? AND u.deleted=0 AND u.status=1" + scope + " ORDER BY u.nickname,u.id", args.toArray());
    }

    private Set<Long> visibleStoreIds(AccountPrincipal actor) {
        if (actor.global("users:read")) return Set.of();
        return stores().stream().map(row -> id(row, "id")).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** The list is intentionally stable even before the first adjustment is created. */
    public List<Map<String, Object>> rules() {
        var actor = read();
        var result = new ArrayList<Map<String, Object>>();
        result.add(Map.of("value", "【积分增加】", "label", "【积分增加】"));
        result.add(Map.of("value", "【积分扣除】", "label", "【积分扣除】"));
        var args = new ArrayList<Object>();
        args.add(actor.tenantId());
        var scope = "";
        if (!actor.global("users:read")) {
            var visibleStores = visibleStoreIds(actor);
            if (visibleStores.isEmpty()) scope = " AND 1=0";
            else {
                scope = " AND department_id IN (" + String.join(",", Collections.nCopies(visibleStores.size(), "?")) + ")";
                args.addAll(visibleStores);
            }
        }
        repo.rows("SELECT DISTINCT point_item FROM biz_staff_points WHERE tenant_id=? AND deleted=0" + scope + " ORDER BY point_item", args.toArray())
            .forEach(row -> {
                String value = text(row, "pointItem");
                if (result.stream().noneMatch(item -> Objects.equals(item.get("value"), value)))
                    result.add(Map.of("value", value, "label", value));
            });
        return result;
    }

    public Map<String, Object> records(String from, String to, Long storeId, String pointItem, String status, int page, int pageSize) {
        var actor = read();
        LocalDate start = parseDate(from, "开始日期格式应为 YYYY-MM-DD");
        LocalDate end = parseDate(to, "结束日期格式应为 YYYY-MM-DD");
        if (start != null && end != null && start.isAfter(end)) throw new ApiException(400, "开始日期不能晚于结束日期");
        if (storeId != null) store(actor, storeId, "users:read");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw new ApiException(400, "积分状态不正确");
        if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 100)
            throw new ApiException(400, "分页参数不正确，每页最多 100 条");

        var conditions = new StringBuilder(" WHERE p.tenant_id=? AND p.deleted=0");
        var args = new ArrayList<Object>();
        args.add(actor.tenantId());
        if (start != null) { conditions.append(" AND p.effective_date>=?"); args.add(start); }
        if (end != null) { conditions.append(" AND p.effective_date<=?"); args.add(end); }
        if (storeId != null) { conditions.append(" AND p.department_id=?"); args.add(storeId); }
        if (pointItem != null && !pointItem.isBlank()) { conditions.append(" AND p.point_item=?"); args.add(pointItem.trim()); }
        if (status != null && !status.isBlank()) { conditions.append(" AND p.status=?"); args.add(status); }
        if (!actor.global("users:read")) {
            var visibleStores = stores().stream().map(row -> id(row, "id")).toList();
            if (visibleStores.isEmpty()) conditions.append(" AND 1=0");
            else {
                conditions.append(" AND p.department_id IN (").append(String.join(",", Collections.nCopies(visibleStores.size(), "?"))).append(")");
                args.addAll(visibleStores);
            }
        }

        long total = repo.count("SELECT COUNT(*) FROM biz_staff_points p" + conditions, args.toArray());
        var listArgs = new ArrayList<>(args);
        listArgs.add(pageSize);
        listArgs.add((long) (page - 1) * pageSize);
        var rows = repo.rows("SELECT p.id,p.department_id,p.user_id,u.nickname employee_name,p.submitted_at,p.effective_date,p.points,p.point_type,p.point_item,p.reason,p.status,p.review_note,p.review_time,d.name store_name," +
                "(SELECT COALESCE(SUM(CASE WHEN bp.point_type='ADD' THEN bp.points WHEN bp.point_type='DEDUCT' THEN -bp.points ELSE 0 END),0) FROM biz_staff_points bp WHERE bp.tenant_id=p.tenant_id AND bp.user_id=p.user_id AND bp.deleted=0 AND bp.status='APPROVED') current_points " +
                "FROM biz_staff_points p JOIN sys_user u ON u.tenant_id=p.tenant_id AND u.id=p.user_id " +
                "LEFT JOIN sys_department d ON d.tenant_id=p.tenant_id AND d.id=p.department_id" + conditions +
                " ORDER BY p.submitted_at DESC,p.id DESC LIMIT ? OFFSET ?", listArgs.toArray());
        var records = rows.stream().map(this::toView).toList();
        return Map.of("records", records, "total", total, "page", page, "pageSize", pageSize);
    }

    private Map<String, Object> toView(Map<String, Object> row) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id(row, "id"));
        value.put("userId", id(row, "userId"));
        value.put("employeeName", text(row, "employeeName"));
        value.put("employeeNo", String.valueOf(id(row, "userId")));
        value.put("storeId", row.get("departmentId") == null ? 0 : id(row, "departmentId"));
        value.put("storeName", row.get("storeName"));
        value.put("submittedAt", row.get("submittedAt"));
        value.put("effectiveDate", row.get("effectiveDate"));
        value.put("points", id(row, "points"));
        value.put("pointType", text(row, "pointType"));
        value.put("pointItem", text(row, "pointItem"));
        value.put("reason", text(row, "reason"));
        value.put("status", text(row, "status"));
        value.put("reviewInfo", row.get("reviewNote"));
        value.put("currentPoints", id(row, "currentPoints"));
        return value;
    }

    private LocalDate parseDate(String value, String message) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException cause) { throw new ApiException(400, message); }
    }

    private LocalDate rowDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(Objects.toString(value));
    }

    private void store(AccountPrincipal actor, long storeId, String permission) {
        actor.requireDepartment(permission, storeId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), storeId) == null)
            throw new ApiException(404, "门店不存在或已停用");
    }

    private void requireUser(long tenant, long userId) {
        if (repo.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND deleted=0 AND status=1", tenant, userId) == null)
            throw new ApiException(404, "员工不存在或已停用");
    }

    private long firstStore(long tenant, long userId) {
        var row = repo.one("SELECT d.id FROM sys_user_department ud JOIN sys_department d ON d.tenant_id=ud.tenant_id AND d.id=ud.department_id " +
            "WHERE ud.tenant_id=? AND ud.user_id=? AND d.type='STORE' AND d.status=1 ORDER BY d.sort_order,d.id LIMIT 1", tenant, userId);
        return row == null ? 0 : id(row, "id");
    }

    private void validate(PointSave input) {
        if (!POINT_TYPES.contains(input.pointType())) throw new ApiException(400, "积分修改类型不正确");
        if (input.points() == null || input.points() <= 0) throw new ApiException(400, "积分数量必须大于 0");
        if (input.reason() == null || input.reason().trim().isBlank()) throw new ApiException(400, "请输入修改理由");
        if (input.effectiveDate() != null && input.effectiveDate().isAfter(LocalDate.now())) throw new ApiException(400, "积分生效日期不能晚于今天");
    }

    @Transactional
    public long save(Long recordId, PointSave input) {
        var actor = write();
        long tenant = actor.tenantId();
        validate(input);
        requireUser(tenant, input.userId());
        var existing = recordId == null ? null : repo.one("SELECT id,department_id,user_id,effective_date FROM biz_staff_points WHERE tenant_id=? AND id=? AND deleted=0", tenant, recordId);
        if (recordId != null && existing == null) throw new ApiException(404, "员工积分记录不存在");
        LocalDate effectiveDate = input.effectiveDate() != null
            ? input.effectiveDate()
            : existing == null ? LocalDate.now() : rowDate(existing.get("effectiveDate"));
        String pointItem = input.pointType().equals("ADD") ? "【积分增加】" : "【积分扣除】";
        String reason = input.reason().trim();
        long departmentId = existing == null || id(existing, "userId") != input.userId() ? firstStore(tenant, input.userId()) : id(existing, "departmentId");
        if (recordId == null) {
            return repo.insert("INSERT INTO biz_staff_points(tenant_id,department_id,user_id,point_type,points,point_item,reason,status,effective_date,reviewer_user_id,review_time,updater_user_id) VALUES(?,?,?,?,?,?,?,'APPROVED',?,?,CURRENT_TIMESTAMP,?)",
                tenant, departmentId, input.userId(), input.pointType(), input.points(), pointItem, reason, effectiveDate, actor.userId(), actor.userId());
        }
        repo.update("UPDATE biz_staff_points SET department_id=?,user_id=?,point_type=?,points=?,point_item=?,reason=?,status='APPROVED',effective_date=?,reviewer_user_id=?,review_time=CURRENT_TIMESTAMP,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND deleted=0",
            departmentId, input.userId(), input.pointType(), input.points(), pointItem, reason, effectiveDate, actor.userId(), actor.userId(), tenant, recordId);
        return recordId;
    }

    @Transactional
    public void delete(long recordId) {
        var actor = write();
        if (repo.update("UPDATE biz_staff_points SET deleted=1,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND deleted=0",
            actor.userId(), actor.tenantId(), recordId) == 0) throw new ApiException(404, "员工积分记录不存在");
    }
}
