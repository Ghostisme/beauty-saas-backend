package com.beauty.saas.inventory;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.InventoryWorkflowRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

/** Persistent inventory support records used by the compact inventory workspace. */
@Service
@RequiredArgsConstructor
public class InventoryWorkflowService {
    private final IamRepository repo;
    private final AccessService access;
    private AccountPrincipal actor(String permission) { var actor = access.currentTenant(); actor.require(permission); return actor; }
    private AccountPrincipal write() {
        var actor = actor("inventory:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", actor.tenantId()) == null) throw new ApiException(409, "企业已停用，请先由平台启用");
        var fresh = access.reload(actor); fresh.require("inventory:write"); return fresh;
    }
    private static void page(int page, int size) { if (page < 1 || page > 100000 || size < 1 || size > 100) throw new ApiException(400, "分页参数不正确，每页最多 100 条"); }
    private static String keyword(String input) { String value = Objects.toString(input, "").trim().toLowerCase(Locale.ROOT); if (value.length() > 100) throw new ApiException(400, "搜索内容不能超过 100 字"); return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }
    private static String scope(AccountPrincipal actor, String permission, String column, List<Object> args) {
        if (actor.global(permission)) return "";
        var allowed = actor.scopes().getOrDefault(permission, Set.of()).stream().filter(id -> id > 0).toList();
        if (allowed.isEmpty()) return " AND 1=0";
        args.addAll(allowed); return " AND " + column + " IN (" + marks(allowed.size()) + ")";
    }
    private void department(AccountPrincipal actor, String permission, Long id) {
        if (id == null || id <= 0) throw new ApiException(400, "门店参数不正确");
        actor.requireDepartment(permission, id);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), id) == null) throw new ApiException(404, "门店不存在或已停用");
    }

    public Page<Map<String,Object>> batches(BatchQuery query) {
        var actor = actor("inventory:read"); page(query.page(), query.pageSize()); var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE b.tenant_id=? AND b.status=1");
        if (query.departmentId() != null) { department(actor, "inventory:read", query.departmentId()); where.append(" AND b.department_id=?"); args.add(query.departmentId()); }
        else where.append(scope(actor, "inventory:read", "b.department_id", args));
        if (query.itemId() != null) { where.append(" AND b.item_id=?"); args.add(query.itemId()); }
        var search = keyword(query.keyword()); where.append(" AND (LOWER(b.batch_name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(b.remark,'')) LIKE ? ESCAPE '!')"); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory_batch b" + where, args.toArray()); args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT b.*,d.name department_name,x.code item_code,x.name item_name,x.unit,x.spec FROM biz_inventory_batch b JOIN sys_department d ON d.tenant_id=b.tenant_id AND d.id=b.department_id JOIN biz_item x ON x.tenant_id=b.tenant_id AND x.id=b.item_id" + where + " ORDER BY b.create_time DESC,b.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    @Transactional public long saveBatch(BatchSave input) {
        var actor = write(); department(actor, "inventory:write", input.departmentId());
        var item = repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND id=? AND kind='PRODUCT' AND status=1", actor.tenantId(), input.itemId()); if (item == null) throw new ApiException(400, "批次只能关联启用中的产品");
        if (input.expiryDate() != null && input.productionDate() != null && input.expiryDate().isBefore(input.productionDate())) throw new ApiException(400, "到期日期不能早于生产日期");
        return repo.insert("INSERT INTO biz_inventory_batch(tenant_id,department_id,item_id,batch_name,production_date,expiry_date,remark,status) VALUES(?,?,?,?,?,?,?,?)", actor.tenantId(), input.departmentId(), input.itemId(), input.batchName().trim(), input.productionDate(), input.expiryDate(), blank(input.remark()), input.status());
    }
    @Transactional public void deleteBatch(long id) { var actor = write(); var row = repo.one("SELECT department_id FROM biz_inventory_batch WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenantId(), id); if (row == null) throw new ApiException(404, "批次不存在"); actor.requireDepartment("inventory:write", id(row, "departmentId")); repo.update("UPDATE biz_inventory_batch SET status=0,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?", actor.tenantId(), id); }

    public Page<Map<String,Object>> documents(DocumentQuery query) {
        var actor = actor("inventory:read"); page(query.page(), query.pageSize()); var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE d.tenant_id=?");
        if (query.docType() != null && !query.docType().isBlank()) { where.append(" AND d.doc_type=?"); args.add(query.docType()); }
        if (query.status() != null && !query.status().isBlank()) { where.append(" AND d.status=?"); args.add(query.status()); }
        if (query.sourceDepartmentId() != null) { department(actor, "inventory:read", query.sourceDepartmentId()); where.append(" AND d.source_department_id=?"); args.add(query.sourceDepartmentId()); }
        else where.append(scope(actor, "inventory:read", "COALESCE(d.source_department_id,d.target_department_id)", args));
        if (query.targetDepartmentId() != null) { department(actor, "inventory:read", query.targetDepartmentId()); where.append(" AND d.target_department_id=?"); args.add(query.targetDepartmentId()); }
        var search = keyword(query.keyword()); where.append(" AND (LOWER(d.document_no) LIKE ? ESCAPE '!' OR LOWER(COALESCE(d.remark,'')) LIKE ? ESCAPE '!')"); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory_doc d" + where, args.toArray()); args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT d.*,s.name source_department_name,t.name target_department_name FROM biz_inventory_doc d LEFT JOIN sys_department s ON s.tenant_id=d.tenant_id AND s.id=d.source_department_id LEFT JOIN sys_department t ON t.tenant_id=d.tenant_id AND t.id=d.target_department_id" + where + " ORDER BY d.document_date DESC,d.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    @Transactional public long saveDocument(DocumentSave input) {
        var actor = write();
        if (input.sourceDepartmentId() != null) department(actor, "inventory:write", input.sourceDepartmentId());
        if (input.targetDepartmentId() != null) department(actor, "inventory:write", input.targetDepartmentId());
        if ("TRANSFER_IN".equals(input.docType()) && input.targetDepartmentId() == null) throw new ApiException(400, "请选择调入门店");
        if ("TRANSFER_OUT".equals(input.docType()) && input.sourceDepartmentId() == null) throw new ApiException(400, "请选择调出门店");
        var number = blank(input.documentNo()); if (number == null) number = "INV-" + System.currentTimeMillis();
        if (repo.one("SELECT id FROM biz_inventory_doc WHERE tenant_id=? AND document_no=?", actor.tenantId(), number) != null) throw new ApiException(409, "单据号已存在");
        return repo.insert("INSERT INTO biz_inventory_doc(tenant_id,doc_type,document_no,source_department_id,target_department_id,document_date,operator_name,status,remark,creator_id) VALUES(?,?,?,?,?,?,?,?,?,?)", actor.tenantId(), input.docType(), number, input.sourceDepartmentId(), input.targetDepartmentId(), input.documentDate(), blank(input.operatorName()), input.status(), blank(input.remark()), actor.userId());
    }

    public Page<Map<String,Object>> account(AccountQuery query) {
        var actor = actor("inventory:read"); page(query.page(), query.pageSize()); var args = new ArrayList<Object>(List.of(actor.tenantId())); var where = new StringBuilder(" WHERE i.tenant_id=?");
        if (query.departmentId() != null) { department(actor, "inventory:read", query.departmentId()); where.append(" AND i.department_id=?"); args.add(query.departmentId()); } else where.append(scope(actor, "inventory:read", "i.department_id", args));
        var search = keyword(query.keyword()); where.append(" AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.category,'')) LIKE ? ESCAPE '!')"); args.add(search); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id" + where, args.toArray()); args.add(query.pageSize()); args.add((query.page()-1)*query.pageSize());
        var rows = repo.rows("SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.category,x.unit,x.spec,i.quantity ending_quantity,i.cost_price FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id" + where + " ORDER BY d.name,x.name LIMIT ? OFFSET ?", args.toArray());
        LocalDate start = parse(query.startDate()); LocalDate end = parse(query.endDate());
        for (var row : rows) {
            var in = new BigDecimal(Objects.toString(repo.one("SELECT COALESCE(SUM(quantity),0) quantity FROM biz_inventory_change WHERE tenant_id=? AND inventory_id=? AND change_type='IN'" + dateWhere("create_time", start, end), concat(actor.tenantId(), id(row,"id"), dateArgs(start,end))).get("quantity"), "0"));
            var out = new BigDecimal(Objects.toString(repo.one("SELECT COALESCE(SUM(quantity),0) quantity FROM biz_inventory_change WHERE tenant_id=? AND inventory_id=? AND change_type='OUT'" + dateWhere("create_time", start, end), concat(actor.tenantId(), id(row,"id"), dateArgs(start,end))).get("quantity"), "0"));
            var ending = new BigDecimal(Objects.toString(row.get("endingQuantity"), "0"));
            row.put("inboundQuantity", in); row.put("outboundQuantity", out); row.put("openingQuantity", ending.subtract(in).add(out));
        }
        return new Page<>(rows, total, query.page(), query.pageSize());
    }
    private static LocalDate parse(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value); } catch (Exception e) { throw new ApiException(400, "日期格式不正确"); } }
    private static String dateWhere(String column, LocalDate start, LocalDate end) { var sql = ""; if (start != null) sql += " AND " + column + ">=?"; if (end != null) sql += " AND " + column + "<?"; return sql; }
    private static Object[] dateArgs(LocalDate start, LocalDate end) { var list = new ArrayList<Object>(); if (start != null) list.add(start.atStartOfDay()); if (end != null) list.add(end.plusDays(1).atStartOfDay()); return list.toArray(); }
    private static Object[] concat(Object first, Object second, Object[] rest) { var result = new Object[rest.length + 2]; result[0] = first; result[1] = second; System.arraycopy(rest, 0, result, 2, rest.length); return result; }
}
