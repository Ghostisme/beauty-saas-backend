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
        return repo.insert("INSERT INTO biz_inventory_batch(tenant_id,department_id,item_id,batch_name,quantity,production_date,expiry_date,remark,status) VALUES(?,?,?,?,?,?,?,?,?)", actor.tenantId(), input.departmentId(), input.itemId(), input.batchName().trim(), input.quantity() == null ? BigDecimal.ZERO : input.quantity(), input.productionDate(), input.expiryDate(), blank(input.remark()), input.status());
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
        if (query.startDate() != null) { where.append(" AND d.document_date>=?"); args.add(query.startDate()); }
        if (query.endDate() != null) { where.append(" AND d.document_date<=?"); args.add(query.endDate()); }
        var search = keyword(query.keyword()); where.append(" AND (LOWER(d.document_no) LIKE ? ESCAPE '!' OR LOWER(COALESCE(d.remark,'')) LIKE ? ESCAPE '!')"); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory_doc d" + where, args.toArray()); args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT d.*,s.name source_department_name,t.name target_department_name,COALESCE(u.nickname,u.username) creator_name FROM biz_inventory_doc d LEFT JOIN sys_department s ON s.tenant_id=d.tenant_id AND s.id=d.source_department_id LEFT JOIN sys_department t ON t.tenant_id=d.tenant_id AND t.id=d.target_department_id LEFT JOIN sys_user u ON u.tenant_id=d.tenant_id AND u.id=d.creator_id" + where + " ORDER BY d.document_date DESC,d.id DESC LIMIT ? OFFSET ?", args.toArray());
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

    /** Returns the real ledger rows behind the stock-row "库存明细" action. */
    public Page<Map<String,Object>> inventoryChangesFor(long inventoryId, int page, int pageSize, LocalDate startDate, LocalDate endDate) {
        var actor = actor("inventory:read"); page(page, pageSize);
        var inventory = repo.one("SELECT department_id FROM biz_inventory WHERE tenant_id=? AND id=?", actor.tenantId(), inventoryId);
        if (inventory == null) throw new ApiException(404, "库存记录不存在");
        actor.requireDepartment("inventory:read", id(inventory, "departmentId"));
        var args = new ArrayList<Object>(List.of(actor.tenantId(), inventoryId));
        var dateFilter = dateWhere("create_time", startDate, endDate);
        var rowDateFilter = dateWhere("c.create_time", startDate, endDate);
        var countArgs = new ArrayList<Object>(args);
        countArgs.addAll(List.of(dateArgs(startDate, endDate)));
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory_change WHERE tenant_id=? AND inventory_id=?" + dateFilter, countArgs.toArray());
        args.addAll(List.of(dateArgs(startDate, endDate)));
        args.add(pageSize); args.add((page - 1) * pageSize);
        var rows = repo.rows("SELECT c.*,d.name department_name,x.code item_code,x.name item_name,x.brand,x.category,x.unit,x.spec "
            + "FROM biz_inventory_change c JOIN sys_department d ON d.tenant_id=c.tenant_id AND d.id=c.department_id "
            + "JOIN biz_item x ON x.tenant_id=c.tenant_id AND x.id=c.item_id "
            + "WHERE c.tenant_id=? AND c.inventory_id=?" + rowDateFilter + " ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, page, pageSize);
    }

    /**
     * Creates a product inbound/outbound document with multiple lines and, when
     * submitted as CONFIRMED, applies every line to the inventory ledger in one
     * transaction. This is the document flow shown by the reference screens.
     */
    @Transactional public long saveMovementDocument(MovementDocumentSave input) {
        var actor = write();
        department(actor, "inventory:write", input.departmentId());
        var docType = "IN".equals(input.changeType()) ? "TRANSFER_IN" : "TRANSFER_OUT";
        var number = blank(input.documentNo());
        if (number == null) number = ("IN".equals(input.changeType()) ? "IN-" : "OUT-") + System.currentTimeMillis();
        if (repo.one("SELECT id FROM biz_inventory_doc WHERE tenant_id=? AND document_no=?", actor.tenantId(), number) != null) {
            throw new ApiException(409, "单据号已存在");
        }
        var documentId = repo.insert(
            "INSERT INTO biz_inventory_doc(tenant_id,doc_type,document_no,source_department_id,target_department_id,document_date,operator_name,status,remark,creator_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), docType,
            number,
            "IN".equals(input.changeType()) ? null : input.departmentId(),
            "IN".equals(input.changeType()) ? input.departmentId() : null,
            input.documentDate(), blank(input.operatorName()), input.status(), blank(input.remark()), actor.userId());

        for (var line : input.lines()) {
            var item = repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND id=? AND kind='PRODUCT' AND status=1", actor.tenantId(), line.itemId());
            if (item == null) throw new ApiException(400, "单据包含不存在或已停用的产品");
            if (line.expiryDate() != null && line.productionDate() != null && line.expiryDate().isBefore(line.productionDate())) {
                throw new ApiException(400, "到期日期不能早于生产日期");
            }
            repo.insert("INSERT INTO biz_inventory_doc_line(tenant_id,document_id,department_id,item_id,quantity,unit_cost,batch_name,production_date,expiry_date,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                actor.tenantId(), documentId, input.departmentId(), line.itemId(), line.quantity(), line.unitCost(), blank(line.batchName()), line.productionDate(), line.expiryDate(), blank(line.remark()));
            if ("CONFIRMED".equals(input.status())) {
                var inventoryId = applyMovement(actor, input.departmentId(), line.itemId(), input.changeType(), line.quantity(), line.unitCost(), number, line.remark());
                if ("IN".equals(input.changeType()) && line.batchName() != null && !line.batchName().isBlank()) {
                    upsertBatch(actor.tenantId(), input.departmentId(), line, line.quantity());
                } else if ("OUT".equals(input.changeType()) && line.batchName() != null && !line.batchName().isBlank()) {
                    consumeBatch(actor.tenantId(), input.departmentId(), line.itemId(), line.batchName(), line.quantity());
                }
                // Keep the relationship explicit for future batch-level consumption flows.
                if (inventoryId <= 0) throw new ApiException(500, "库存流水写入失败");
            }
        }
        return documentId;
    }

    @Transactional public long saveLiquidationDocument(LiquidationDocumentSave input) {
        var actor = write();
        department(actor, "inventory:write", input.departmentId());
        var number = blank(input.documentNo());
        if (number == null) number = "ST-" + System.currentTimeMillis();
        if (repo.one("SELECT id FROM biz_inventory_doc WHERE tenant_id=? AND document_no=?", actor.tenantId(), number) != null) {
            throw new ApiException(409, "单据号已存在");
        }
        var documentId = repo.insert(
            "INSERT INTO biz_inventory_doc(tenant_id,doc_type,document_no,source_department_id,target_department_id,document_date,operator_name,status,remark,creator_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), "LIQUIDATION", number, input.departmentId(), null,
            input.documentDate(), blank(input.operatorName()), input.status(), blank(input.remark()), actor.userId());
        for (var line : input.lines()) {
            var item = repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND id=? AND kind='PRODUCT' AND status=1", actor.tenantId(), line.itemId());
            if (item == null) throw new ApiException(400, "盘点单包含不存在或已停用的产品");
            var inventory = repo.one("SELECT id,quantity,cost_price FROM biz_inventory WHERE tenant_id=? AND department_id=? AND item_id=? FOR UPDATE",
                actor.tenantId(), input.departmentId(), line.itemId());
            var bookQuantity = inventory == null ? BigDecimal.ZERO : new BigDecimal(Objects.toString(inventory.get("quantity"), "0"));
            repo.insert("INSERT INTO biz_inventory_liquidation_line(tenant_id,document_id,department_id,item_id,book_quantity,actual_quantity,remark) VALUES(?,?,?,?,?,?,?)",
                actor.tenantId(), documentId, input.departmentId(), line.itemId(), bookQuantity, line.actualQuantity(), blank(line.remark()));
            if ("CONFIRMED".equals(input.status()) && Boolean.TRUE.equals(input.syncInventory())) {
                applyAdjustment(actor, input.departmentId(), line.itemId(), line.actualQuantity(), inventory, number, line.remark());
            }
        }
        return documentId;
    }

    private long applyMovement(AccountPrincipal actor, long departmentId, long itemId, String type,
                               BigDecimal quantity, BigDecimal unitCost, String referenceNo, String reason) {
        var row = repo.one("SELECT * FROM biz_inventory WHERE tenant_id=? AND department_id=? AND item_id=? FOR UPDATE", actor.tenantId(), departmentId, itemId);
        var current = row == null ? BigDecimal.ZERO : new BigDecimal(Objects.toString(row.get("quantity"), "0"));
        var next = "IN".equals(type) ? current.add(quantity) : current.subtract(quantity);
        if (next.signum() < 0) throw new ApiException(409, "库存不足，不能出库");
        final long inventoryId;
        if (row == null) {
            inventoryId = repo.insert("INSERT INTO biz_inventory(tenant_id,department_id,item_id,quantity,cost_price,warning_value,version) VALUES(?,?,?,?,?,?,0)",
                actor.tenantId(), departmentId, itemId, next, unitCost, BigDecimal.ZERO);
        } else {
            inventoryId = id(row, "id");
            repo.update("UPDATE biz_inventory SET quantity=?,cost_price=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                next, unitCost, actor.tenantId(), inventoryId);
        }
        repo.insert("INSERT INTO biz_inventory_change(tenant_id,inventory_id,department_id,item_id,change_type,quantity,unit_cost,reason,reference_no,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), inventoryId, departmentId, itemId, type, quantity, unitCost, blank(reason), referenceNo, actor.userId());
        return inventoryId;
    }

    private long applyAdjustment(AccountPrincipal actor, long departmentId, long itemId, BigDecimal actualQuantity,
                                 Map<String, Object> row, String referenceNo, String reason) {
        var current = row == null ? BigDecimal.ZERO : new BigDecimal(Objects.toString(row.get("quantity"), "0"));
        if (actualQuantity.compareTo(current) == 0) return row == null ? 0 : id(row, "id");
        var unitCost = row == null ? BigDecimal.ZERO : new BigDecimal(Objects.toString(row.get("costPrice"), "0"));
        final long inventoryId;
        if (row == null) {
            inventoryId = repo.insert("INSERT INTO biz_inventory(tenant_id,department_id,item_id,quantity,cost_price,warning_value,version) VALUES(?,?,?,?,?,?,0)",
                actor.tenantId(), departmentId, itemId, actualQuantity, unitCost, BigDecimal.ZERO);
        } else {
            inventoryId = id(row, "id");
            repo.update("UPDATE biz_inventory SET quantity=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                actualQuantity, actor.tenantId(), inventoryId);
        }
        repo.insert("INSERT INTO biz_inventory_change(tenant_id,inventory_id,department_id,item_id,change_type,quantity,unit_cost,reason,reference_no,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), inventoryId, departmentId, itemId, "ADJUST", actualQuantity.subtract(current).abs(), unitCost, blank(reason), referenceNo, actor.userId());
        return inventoryId;
    }

    private void upsertBatch(long tenantId, long departmentId, DocumentLineSave line, BigDecimal quantity) {
        var existing = repo.one("SELECT id FROM biz_inventory_batch WHERE tenant_id=? AND department_id=? AND item_id=? AND batch_name=? AND status=1",
            tenantId, departmentId, line.itemId(), line.batchName().trim());
        if (existing == null) {
            repo.insert("INSERT INTO biz_inventory_batch(tenant_id,department_id,item_id,batch_name,quantity,production_date,expiry_date,remark,status) VALUES(?,?,?,?,?,?,?,?,1)",
                tenantId, departmentId, line.itemId(), line.batchName().trim(), quantity, line.productionDate(), line.expiryDate(), blank(line.remark()));
        } else {
            repo.update("UPDATE biz_inventory_batch SET quantity=quantity+?,production_date=COALESCE(production_date,?),expiry_date=COALESCE(expiry_date,?),update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                quantity, line.productionDate(), line.expiryDate(), tenantId, id(existing, "id"));
        }
    }

    private void consumeBatch(long tenantId, long departmentId, long itemId, String batchName, BigDecimal quantity) {
        var row = repo.one("SELECT id,quantity FROM biz_inventory_batch WHERE tenant_id=? AND department_id=? AND item_id=? AND batch_name=? AND status=1 FOR UPDATE",
            tenantId, departmentId, itemId, batchName.trim());
        if (row == null) throw new ApiException(409, "出库批次不存在或已停用");
        var current = new BigDecimal(Objects.toString(row.get("quantity"), "0"));
        if (current.compareTo(quantity) < 0) throw new ApiException(409, "批次库存不足，不能出库");
        repo.update("UPDATE biz_inventory_batch SET quantity=quantity-?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
            quantity, tenantId, id(row, "id"));
    }

    public Page<Map<String,Object>> account(AccountQuery query) {
        var actor = actor("inventory:read"); page(query.page(), query.pageSize()); var args = new ArrayList<Object>(List.of(actor.tenantId())); var where = new StringBuilder(" WHERE i.tenant_id=?");
        if (query.departmentId() != null) { department(actor, "inventory:read", query.departmentId()); where.append(" AND i.department_id=?"); args.add(query.departmentId()); } else where.append(scope(actor, "inventory:read", "i.department_id", args));
        if (query.brand() != null && !query.brand().isBlank()) { where.append(" AND LOWER(COALESCE(x.brand,''))=?"); args.add(query.brand().trim().toLowerCase(Locale.ROOT)); }
        if (query.category() != null && !query.category().isBlank()) { where.append(" AND LOWER(COALESCE(x.category,''))=?"); args.add(query.category().trim().toLowerCase(Locale.ROOT)); }
        var search = keyword(query.keyword()); where.append(" AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.brand,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.category,'')) LIKE ? ESCAPE '!')"); args.add(search); args.add(search); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id" + where, args.toArray()); args.add(query.pageSize()); args.add((query.page()-1)*query.pageSize());
        var rows = repo.rows("SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.brand,x.category,x.unit,x.spec,i.quantity ending_quantity,i.cost_price FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id" + where + " ORDER BY d.name,x.name LIMIT ? OFFSET ?", args.toArray());
        LocalDate start = parse(query.startDate()); LocalDate end = parse(query.endDate());
        for (var row : rows) {
            var in = new BigDecimal(Objects.toString(repo.one("SELECT COALESCE(SUM(quantity),0) quantity FROM biz_inventory_change WHERE tenant_id=? AND inventory_id=? AND change_type='IN'" + dateWhere("create_time", start, end), concat(actor.tenantId(), id(row,"id"), dateArgs(start,end))).get("quantity"), "0"));
            var out = new BigDecimal(Objects.toString(repo.one("SELECT COALESCE(SUM(quantity),0) quantity FROM biz_inventory_change WHERE tenant_id=? AND inventory_id=? AND change_type='OUT'" + dateWhere("create_time", start, end), concat(actor.tenantId(), id(row,"id"), dateArgs(start,end))).get("quantity"), "0"));
            var ending = new BigDecimal(Objects.toString(row.get("endingQuantity"), "0"));
            row.put("inboundQuantity", in); row.put("outboundQuantity", out); row.put("openingQuantity", ending.subtract(in).add(out));
        }
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    public Map<String,Object> settings() {
        var actor = actor("inventory:read");
        actor.requireGlobal("inventory:read");
        return settingsRow(actor.tenantId());
    }

    @Transactional public Map<String,Object> saveSettings(InventorySettingsSave input) {
        var actor = write();
        actor.requireGlobal("inventory:write");
        var current = settingsRow(actor.tenantId());
        var version = id(current, "version");
        if (version != input.version()) throw new ApiException(409, "库存设置已被其他操作更新，请刷新后重试");
        var existing = repo.one("SELECT tenant_id FROM biz_inventory_setting WHERE tenant_id=? FOR UPDATE", actor.tenantId());
        Object[] values = {
            actor.tenantId(), input.preventOrderOnShortage(), input.transferAutoConfirmEnabled(), input.transferAutoConfirmDays(),
            input.stockAlertEnabled(), input.stockAlertValue(), input.expiryAlertEnabled(), input.expiryAlertMonths(),
            input.salesDeductInventory(), input.deleteProductSyncInventory(), actor.userId()
        };
        if (existing == null) {
            repo.update("INSERT INTO biz_inventory_setting(tenant_id,prevent_order_on_shortage,transfer_auto_confirm_enabled,transfer_auto_confirm_days,stock_alert_enabled,stock_alert_value,expiry_alert_enabled,expiry_alert_months,sales_deduct_inventory,delete_product_sync_inventory,version,updated_by,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,CURRENT_TIMESTAMP)", values);
        } else {
            var changed = repo.update("UPDATE biz_inventory_setting SET prevent_order_on_shortage=?,transfer_auto_confirm_enabled=?,transfer_auto_confirm_days=?,stock_alert_enabled=?,stock_alert_value=?,expiry_alert_enabled=?,expiry_alert_months=?,sales_deduct_inventory=?,delete_product_sync_inventory=?,version=version+1,updated_by=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND version=?",
                input.preventOrderOnShortage(), input.transferAutoConfirmEnabled(), input.transferAutoConfirmDays(), input.stockAlertEnabled(), input.stockAlertValue(), input.expiryAlertEnabled(), input.expiryAlertMonths(), input.salesDeductInventory(), input.deleteProductSyncInventory(), actor.userId(), actor.tenantId(), version);
            if (changed != 1) throw new ApiException(409, "库存设置已被其他操作更新，请刷新后重试");
        }
        return settingsRow(actor.tenantId());
    }

    private Map<String,Object> settingsRow(long tenantId) {
        var row = repo.one("SELECT prevent_order_on_shortage,transfer_auto_confirm_enabled,transfer_auto_confirm_days,stock_alert_enabled,stock_alert_value,expiry_alert_enabled,expiry_alert_months,sales_deduct_inventory,delete_product_sync_inventory,version FROM biz_inventory_setting WHERE tenant_id=?", tenantId);
        var result = new LinkedHashMap<String,Object>();
        if (row == null) {
            result.put("preventOrderOnShortage", false);
            result.put("transferAutoConfirmEnabled", false);
            result.put("transferAutoConfirmDays", 0);
            result.put("stockAlertEnabled", false);
            result.put("stockAlertValue", BigDecimal.ZERO);
            result.put("expiryAlertEnabled", false);
            result.put("expiryAlertMonths", 6);
            result.put("salesDeductInventory", true);
            result.put("deleteProductSyncInventory", true);
            result.put("version", 0L);
            return result;
        }
        result.put("preventOrderOnShortage", flag(row, "preventOrderOnShortage"));
        result.put("transferAutoConfirmEnabled", flag(row, "transferAutoConfirmEnabled"));
        result.put("transferAutoConfirmDays", id(row, "transferAutoConfirmDays"));
        result.put("stockAlertEnabled", flag(row, "stockAlertEnabled"));
        result.put("stockAlertValue", row.get("stockAlertValue"));
        result.put("expiryAlertEnabled", flag(row, "expiryAlertEnabled"));
        result.put("expiryAlertMonths", id(row, "expiryAlertMonths"));
        result.put("salesDeductInventory", flag(row, "salesDeductInventory"));
        result.put("deleteProductSyncInventory", flag(row, "deleteProductSyncInventory"));
        result.put("version", id(row, "version"));
        return result;
    }

    private static boolean flag(Map<String,Object> row, String name) {
        var value = row.get(name);
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
    private static LocalDate parse(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value); } catch (Exception e) { throw new ApiException(400, "日期格式不正确"); } }
    private static String dateWhere(String column, LocalDate start, LocalDate end) { var sql = ""; if (start != null) sql += " AND " + column + ">=?"; if (end != null) sql += " AND " + column + "<?"; return sql; }
    private static Object[] dateArgs(LocalDate start, LocalDate end) { var list = new ArrayList<Object>(); if (start != null) list.add(start.atStartOfDay()); if (end != null) list.add(end.plusDays(1).atStartOfDay()); return list.toArray(); }
    private static Object[] concat(Object first, Object second, Object[] rest) { var result = new Object[rest.length + 2]; result[0] = first; result[1] = second; System.arraycopy(rest, 0, result, 2, rest.length); return result; }
}
