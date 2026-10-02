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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    private static String time(Object value) { return value == null ? "" : value.toString().substring(0, 5); }
    private static String csv(Collection<?> values) { return String.join(",", values.stream().map(Object::toString).toList()); }
    private static boolean flag(Boolean value) { return Boolean.TRUE.equals(value); }
    private static String rowText(Map<String,Object> row, String name) { return IamRepository.text(row, name); }

    /** Options are scoped by the same appointment permission as the calendar. */
    public Map<String,Object> options(Long departmentId) {
        var actor = actor("appointments:read");
        var departments = new ArrayList<Long>();
        if (departmentId != null) {
            actor.requireDepartment("appointments:read", departmentId);
            departments.add(departmentId);
        } else if (!actor.global("appointments:read")) {
            departments.addAll(actor.scopes().getOrDefault("appointments:read", Set.of()).stream().filter(id -> id > 0).toList());
        }
        if (departmentId == null && !actor.global("appointments:read") && departments.isEmpty())
            return Map.of("staff", List.of(), "rooms", List.of(), "services", List.of());
        var staffArgs = new ArrayList<Object>(List.of(actor.tenantId()));
        var staffWhere = new StringBuilder(" WHERE u.tenant_id=? AND u.deleted=0 AND u.status=1");
        if (!departments.isEmpty()) { staffWhere.append(" AND ud.department_id IN (").append(marks(departments.size())).append(")"); staffArgs.addAll(departments); }
        var staff = repo.rows("SELECT DISTINCT u.id,u.nickname,u.username FROM sys_user u JOIN sys_user_department ud ON ud.tenant_id=u.tenant_id AND ud.user_id=u.id" + staffWhere + " ORDER BY u.nickname,u.id", staffArgs.toArray());
        var roomArgs = new ArrayList<Object>(List.of(actor.tenantId()));
        var roomWhere = new StringBuilder(" WHERE r.tenant_id=? AND r.status=1");
        if (!departments.isEmpty()) { roomWhere.append(" AND dr.department_id IN (").append(marks(departments.size())).append(")"); roomArgs.addAll(departments); }
        var rooms = repo.rows("SELECT DISTINCT r.id,r.name,r.code,dr.department_id FROM sys_room r JOIN sys_department_room dr ON dr.tenant_id=r.tenant_id AND dr.room_id=r.id" + roomWhere + " ORDER BY r.name,r.id", roomArgs.toArray());
        var services = repo.rows("SELECT id,code,name,duration_minutes durationMinutes FROM biz_item WHERE tenant_id=? AND kind='PROJECT' AND status=1 ORDER BY name,id", actor.tenantId());
        return Map.of("staff", staff, "rooms", rooms, "services", services);
    }

    public Page<Map<String,Object>> list(AppointmentQuery query) {
        var actor = actor("appointments:read"); page(query.page(), query.pageSize());
        if (query.startDate() != null && query.endDate() != null && query.startDate().isAfter(query.endDate()))
            throw new ApiException(400, "日期范围不正确");
        var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE a.tenant_id=?");
        if (query.date() != null) { where.append(" AND a.appointment_date=?"); args.add(query.date()); }
        if (query.startDate() != null) { where.append(" AND a.appointment_date>=?"); args.add(query.startDate()); }
        if (query.endDate() != null) { where.append(" AND a.appointment_date<=?"); args.add(query.endDate()); }
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
        if (text(query.keyword()) != null) {
            var search = "%" + query.keyword().trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            var field = switch (Objects.toString(query.searchBy(), "ALL").toUpperCase(Locale.ROOT)) {
                case "NAME" -> "LOWER(a.customer_name)";
                case "PHONE" -> "LOWER(COALESCE(a.phone,''))";
                case "ID" -> "CAST(a.id AS CHAR)";
                case "ALL" -> null;
                default -> throw new ApiException(400, "搜索类型不正确");
            };
            if (field == null) {
                where.append(" AND (LOWER(a.customer_name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(a.phone,'')) LIKE ? ESCAPE '!' OR CAST(a.id AS CHAR) LIKE ? ESCAPE '!')");
                args.add(search); args.add(search); args.add(search);
            } else { where.append(" AND ").append(field).append(" LIKE ? ESCAPE '!'"); args.add(search); }
        }
        var total = repo.count("SELECT COUNT(*) FROM biz_appointment a" + where, args.toArray());
        args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT a.*,d.name department_name FROM biz_appointment a JOIN sys_department d ON d.tenant_id=a.tenant_id AND d.id=a.department_id" + where + " ORDER BY a.appointment_date DESC,a.start_time,a.id LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    private void requireStore(AccountPrincipal actor, long departmentId, String permission) {
        actor.requireDepartment(permission, departmentId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), departmentId) == null)
            throw new ApiException(404, "门店不存在或已停用");
    }

    /** Booking configuration belongs to one tenant and one store. */
    public Map<String,Object> settings(long departmentId) {
        var actor = actor("appointments:read");
        requireStore(actor, departmentId, "appointments:read");
        var row = repo.one("SELECT * FROM biz_appointment_settings WHERE tenant_id=? AND department_id=?", actor.tenantId(), departmentId);
        var result = new LinkedHashMap<String,Object>();
        result.put("departmentId", departmentId);
        result.put("businessStart", row == null ? "09:00" : time(row.get("businessStart")));
        result.put("businessEnd", row == null ? "19:30" : time(row.get("businessEnd")));
        result.put("bookableStaffIds", row == null ? List.of() : parseLongs(rowText(row, "staffIds")));
        result.put("publicBookingEnabled", row == null || flagValue(row.get("publicBookingEnabled")));
        result.put("publicImageData", row == null ? null : row.get("publicImageData"));
        result.put("publicSlots", row == null ? List.of(Map.of("start", "09:00", "end", "19:30")) : parseSlots(rowText(row, "publicSlots")));
        result.put("publicWeekdays", row == null ? List.of(1,2,3,4,5,6,7) : parseInts(rowText(row, "publicWeekdays")));
        result.put("closedDates", row == null || rowText(row, "closedDates").isBlank() ? List.of() : Arrays.asList(rowText(row, "closedDates").split(",")));
        result.put("advanceMinutes", row == null ? 120 : row.get("advanceMinutes"));
        result.put("intervalMinutes", row == null ? 20 : row.get("intervalMinutes"));
        result.put("maxAdvanceDays", row == null ? 30 : row.get("maxAdvanceDays"));
        result.put("preventConflicts", row == null || flagValue(row.get("preventConflicts")));
        return result;
    }

    private static boolean flagValue(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }
    private static List<Long> parseLongs(String value) { return value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(Long::parseLong).toList(); }
    private static List<Integer> parseInts(String value) { return value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(Integer::parseInt).toList(); }
    private static List<Map<String,String>> parseSlots(String value) {
        if (value.isBlank()) return List.of();
        return Arrays.stream(value.split(";")).map(slot -> {
            var parts = slot.split("-");
            return Map.of("start", parts[0], "end", parts[1]);
        }).toList();
    }

    @Transactional public Map<String,Object> saveSettings(AppointmentSettingsSave input) {
        var actor = write();
        requireStore(actor, input.departmentId(), "appointments:write");
        if (!input.businessStart().isBefore(input.businessEnd())) throw new ApiException(400, "门店预约时间不正确");
        var sortedSlots = new ArrayList<>(input.publicSlots());
        sortedSlots.sort(Comparator.comparing(AppointmentTimeSlot::start));
        LocalTime previousEnd = null;
        for (var slot : sortedSlots) {
            if (!slot.start().isBefore(slot.end()) || slot.start().isBefore(input.businessStart()) || slot.end().isAfter(input.businessEnd()) || (previousEnd != null && slot.start().isBefore(previousEnd)))
                throw new ApiException(400, "公众号可预约时间段超出营业时间或发生重叠");
            previousEnd = slot.end();
        }
        for (var staffId : input.bookableStaffIds()) {
            if (repo.one("SELECT u.id FROM sys_user u JOIN sys_user_department ud ON ud.tenant_id=u.tenant_id AND ud.user_id=u.id WHERE u.tenant_id=? AND u.id=? AND ud.department_id=? AND u.deleted=0 AND u.status=1", actor.tenantId(), staffId, input.departmentId()) == null)
                throw new ApiException(400, "预约技师不属于当前门店");
        }
        var image = text(input.publicImageData());
        if (image != null && (!image.matches("(?s)data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+") || image.length() > 1_500_000))
            throw new ApiException(400, "公众号预约图片格式或大小不正确");
        var slotText = String.join(";", sortedSlots.stream().map(slot -> slot.start().format(DateTimeFormatter.ofPattern("HH:mm")) + "-" + slot.end().format(DateTimeFormatter.ofPattern("HH:mm"))).toList());
        var weekdays = input.publicWeekdays().stream().distinct().sorted().toList();
        var closed = input.closedDates().stream().distinct().sorted().toList();
        var row = repo.one("SELECT id FROM biz_appointment_settings WHERE tenant_id=? AND department_id=? FOR UPDATE", actor.tenantId(), input.departmentId());
        if (row == null) {
            repo.insert("INSERT INTO biz_appointment_settings(tenant_id,department_id,business_start,business_end,staff_ids,public_booking_enabled,public_image_data,public_slots,public_weekdays,closed_dates,advance_minutes,interval_minutes,max_advance_days,prevent_conflicts) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                actor.tenantId(), input.departmentId(), input.businessStart(), input.businessEnd(), csv(input.bookableStaffIds()), input.publicBookingEnabled(), image, slotText, csv(weekdays), csv(closed), input.advanceMinutes(), input.intervalMinutes(), input.maxAdvanceDays(), input.preventConflicts());
        } else {
            repo.update("UPDATE biz_appointment_settings SET business_start=?,business_end=?,staff_ids=?,public_booking_enabled=?,public_image_data=?,public_slots=?,public_weekdays=?,closed_dates=?,advance_minutes=?,interval_minutes=?,max_advance_days=?,prevent_conflicts=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND department_id=?",
                input.businessStart(), input.businessEnd(), csv(input.bookableStaffIds()), input.publicBookingEnabled(), image, slotText, csv(weekdays), csv(closed), input.advanceMinutes(), input.intervalMinutes(), input.maxAdvanceDays(), input.preventConflicts(), actor.tenantId(), input.departmentId());
        }
        return settings(input.departmentId());
    }

    @Transactional public long save(Long id, AppointmentSave input) {
        var actor = write();
        requireStore(actor, input.departmentId(), "appointments:write");
        if (input.startTime().plusMinutes(input.durationMinutes()).isAfter(LocalTime.of(23, 59))) throw new ApiException(400, "预约结束时间不能超过当天");
        if (id == null) {
            ensureAvailable(actor, input, null);
            return insert(actor, input, null);
        }
        var existing = repo.one("SELECT id FROM biz_appointment WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenantId(), id);
        if (existing == null) throw new ApiException(404, "预约不存在");
        ensureAvailable(actor, input, id);
        repo.update("UPDATE biz_appointment SET department_id=?,appointment_date=?,start_time=?,duration_minutes=?,customer_name=?,phone=?,service_name=?,staff_name=?,room_name=?,status=?,color=?,note=?,party_size=?,needs_tea=?,needs_air_conditioner=?,brings_pet=?,brings_child=?,needs_bath=?,booking_type=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
            input.departmentId(), input.appointmentDate(), input.startTime(), input.durationMinutes(), input.customerName().trim(), text(input.phone()), text(input.serviceName()), text(input.staffName()), text(input.roomName()), input.status(), input.color(), text(input.note()), value(input.partySize(), 1), flag(input.needsTea()), flag(input.needsAirConditioner()), flag(input.bringsPet()), flag(input.bringsChild()), flag(input.needsBath()), Objects.toString(input.bookingType(), "NORMAL"), actor.tenantId(), id);
        return id;
    }

    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }

    private long insert(AccountPrincipal actor, AppointmentSave input, String seriesId) {
        return repo.insert("INSERT INTO biz_appointment(tenant_id,department_id,appointment_date,start_time,duration_minutes,customer_name,phone,service_name,staff_name,room_name,status,color,note,party_size,needs_tea,needs_air_conditioner,brings_pet,brings_child,needs_bath,booking_type,series_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), input.departmentId(), input.appointmentDate(), input.startTime(), input.durationMinutes(), input.customerName().trim(), text(input.phone()), text(input.serviceName()), text(input.staffName()), text(input.roomName()), input.status(), input.color(), text(input.note()), value(input.partySize(), 1), flag(input.needsTea()), flag(input.needsAirConditioner()), flag(input.bringsPet()), flag(input.bringsChild()), flag(input.needsBath()), Objects.toString(input.bookingType(), "NORMAL"), seriesId);
    }

    private void ensureAvailable(AccountPrincipal actor, AppointmentSave input, Long excludeId) {
        var settings = repo.one("SELECT prevent_conflicts FROM biz_appointment_settings WHERE tenant_id=? AND department_id=?", actor.tenantId(), input.departmentId());
        if (settings != null && !flagValue(settings.get("preventConflicts"))) return;
        var args = new ArrayList<Object>(List.of(actor.tenantId(), input.departmentId(), input.appointmentDate()));
        var rows = repo.rows("SELECT id,start_time,duration_minutes,staff_name,room_name FROM biz_appointment WHERE tenant_id=? AND department_id=? AND appointment_date=? AND status NOT IN ('CANCELLED','DONE')", args.toArray());
        var start = input.startTime();
        var end = start.plusMinutes(input.durationMinutes());
        for (var row : rows) {
            if (excludeId != null && id(row, "id") == excludeId) continue;
            var staffMatch = text(input.staffName()) != null && Objects.equals(text(input.staffName()), rowText(row, "staffName"));
            var roomMatch = text(input.roomName()) != null && Objects.equals(text(input.roomName()), rowText(row, "roomName"));
            if (!staffMatch && !roomMatch) continue;
            var existingStart = row.get("startTime") instanceof java.sql.Time sqlTime ? sqlTime.toLocalTime() : LocalTime.parse(rowText(row, "startTime").substring(0, 5));
            var existingEnd = existingStart.plusMinutes(((Number) row.get("durationMinutes")).longValue());
            if (start.isBefore(existingEnd) && existingStart.isBefore(end)) throw new ApiException(409, "预约时间与当前技师或房间已有安排冲突");
        }
    }

    @Transactional public List<Long> saveRecurring(RecurringAppointmentSave request) {
        var input = request.appointment();
        var actor = write();
        requireStore(actor, input.departmentId(), "appointments:write");
        var seriesId = UUID.randomUUID().toString();
        var ids = new ArrayList<Long>();
        for (int index = 0; index < request.occurrences(); index++) {
            var value = new AppointmentSave(input.departmentId(), input.appointmentDate().plusWeeks(index), input.startTime(), input.durationMinutes(), input.customerName(), input.phone(), input.serviceName(), input.staffName(), input.roomName(), input.status(), input.color(), input.note(), input.partySize(), input.needsTea(), input.needsAirConditioner(), input.bringsPet(), input.bringsChild(), input.needsBath(), "RECURRING");
            ensureAvailable(actor, value, null);
            ids.add(insert(actor, value, seriesId));
        }
        return ids;
    }

    @Transactional public void delete(long id) {
        var actor = write();
        var row = repo.one("SELECT department_id FROM biz_appointment WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenantId(), id);
        if (row == null) throw new ApiException(404, "预约不存在");
        actor.requireDepartment("appointments:write", id(row, "departmentId"));
        if (repo.update("DELETE FROM biz_appointment WHERE tenant_id=? AND id=?", actor.tenantId(), id) == 0) throw new ApiException(404, "预约不存在");
    }
}
