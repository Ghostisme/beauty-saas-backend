package com.beauty.saas.inventory;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.InventoryCostRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import com.beauty.saas.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.beauty.saas.iam.IamRepository.id;

/**
 * Moving-weighted-average cost accounting and unit-cost adjustment workflow.
 *
 * Quantity-changing inventory rows remain in biz_inventory_change.  Cost-only
 * corrections are kept in biz_inventory_cost_adjustment_line so they do not
 * masquerade as stock movement and do not violate the positive-quantity
 * constraint on the inventory ledger.
 */
@Service
@RequiredArgsConstructor
public class InventoryCostService {
    private final IamRepository repo;
    private final AccessService access;

    private record CostState(BigDecimal quantity, BigDecimal totalCost) {
        static CostState empty() { return new CostState(BigDecimal.ZERO, BigDecimal.ZERO); }
        BigDecimal unitCost() { return quantity.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(quantity, 8, RoundingMode.HALF_UP); }
    }

    private record CostEvent(long id, LocalDate date, LocalDateTime time, String kind,
                             BigDecimal quantity, BigDecimal unitCost, BigDecimal targetQuantity,
                             String documentNo, String remark) {}

    private record Timeline(CostState opening, CostState ending, BigDecimal inboundQuantity,
                            BigDecimal inboundCost, BigDecimal outboundQuantity, BigDecimal outboundCost,
                            List<Map<String, Object>> details) {}

    private AccountPrincipal actor(String permission) {
        var actor = access.currentTenant();
        actor.require(permission);
        return actor;
    }

    private AccountPrincipal write() {
        var actor = actor("inventory:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", actor.tenantId()) == null) {
            throw new ApiException(409, "企业已停用，请先由平台启用");
        }
        var fresh = access.reload(actor);
        fresh.require("inventory:write");
        return fresh;
    }

    private static void page(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new ApiException(400, "分页参数不正确，每页最多 100 条");
        }
    }

    private static String keyword(String input) {
        var value = Objects.toString(input, "").trim().toLowerCase(Locale.ROOT);
        if (value.length() > 100) throw new ApiException(400, "搜索内容不能超过 100 字");
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    private static String scope(AccountPrincipal actor, String permission, String column, List<Object> args) {
        if (actor.global(permission)) return "";
        var allowed = actor.scopes().getOrDefault(permission, Set.of()).stream().filter(value -> value > 0).toList();
        if (allowed.isEmpty()) return " AND 1=0";
        args.addAll(allowed);
        return " AND " + column + " IN (" + marks(allowed.size()) + ")";
    }

    private void department(AccountPrincipal actor, String permission, Long departmentId) {
        if (departmentId == null || departmentId <= 0) throw new ApiException(400, "仓库参数不正确");
        actor.requireDepartment(permission, departmentId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), departmentId) == null) {
            throw new ApiException(404, "仓库不存在或已停用");
        }
    }

    public Map<String, Object> accounting(CostQuery query) {
        var actor = actor("inventory:read");
        page(query.page(), query.pageSize());
        var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE i.tenant_id=?");
        if (query.departmentId() != null) {
            department(actor, "inventory:read", query.departmentId());
            where.append(" AND i.department_id=?");
            args.add(query.departmentId());
        } else {
            where.append(scope(actor, "inventory:read", "i.department_id", args));
        }
        if (query.brand() != null && !query.brand().isBlank()) {
            where.append(" AND LOWER(COALESCE(x.brand,''))=?");
            args.add(query.brand().trim().toLowerCase(Locale.ROOT));
        }
        if (query.category() != null && !query.category().isBlank()) {
            where.append(" AND LOWER(COALESCE(x.category,''))=?");
            args.add(query.category().trim().toLowerCase(Locale.ROOT));
        }
        var search = keyword(query.keyword());
        where.append(" AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.brand,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.category,'')) LIKE ? ESCAPE '!')");
        args.add(search); args.add(search); args.add(search); args.add(search);

        var select = "SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.brand,x.category,x.unit,x.spec,i.quantity inventory_quantity,i.cost_price inventory_cost_price "
            + "FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id"
            + where + " ORDER BY d.name,x.name,i.id";
        var allRows = repo.rows(select, args.toArray());

        // Summary cards describe the complete filtered result, not just the
        // ten rows currently visible on the page.
        var summary = summaryZero();
        for (var row : allRows) {
            var timeline = timeline(actor.tenantId(), id(row, "id"), parse(query.startDate()), parse(query.endDate()), false);
            decorateAccountingRow(row, timeline);
            add(summary, "openingCost", timeline.opening().totalCost());
            add(summary, "inboundCost", timeline.inboundCost());
            add(summary, "outboundCost", timeline.outboundCost());
            add(summary, "endingCost", timeline.ending().totalCost());
        }
        var total = allRows.size();
        var from = Math.min((query.page() - 1) * query.pageSize(), total);
        var to = Math.min(from + query.pageSize(), total);
        var rows = allRows.subList(from, to);
        summary.replaceAll((key, value) -> money((BigDecimal) value));
        var result = new LinkedHashMap<String, Object>();
        result.put("records", rows);
        result.put("total", total);
        result.put("page", query.page());
        result.put("pageSize", query.pageSize());
        result.put("summary", summary);
        result.put("startDate", query.startDate());
        result.put("endDate", query.endDate());
        return result;
    }

    public Map<String, Object> detail(CostDetailQuery query) {
        var actor = actor("inventory:read");
        page(query.page(), query.pageSize());
        var inventory = repo.one("SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.brand,x.category,x.unit,x.spec,i.quantity,i.cost_price FROM biz_inventory i JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id WHERE i.tenant_id=? AND i.id=?", actor.tenantId(), query.inventoryId());
        if (inventory == null) throw new ApiException(404, "库存记录不存在");
        actor.requireDepartment("inventory:read", id(inventory, "departmentId"));
        var start = parse(query.startDate());
        var end = parse(query.endDate());
        var timeline = timeline(actor.tenantId(), query.inventoryId(), start, end, true);
        var details = timeline.details();
        var from = Math.min((query.page() - 1) * query.pageSize(), details.size());
        var to = Math.min(from + query.pageSize(), details.size());

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("inventoryId", query.inventoryId());
        metadata.put("departmentId", inventory.get("departmentId"));
        metadata.put("departmentName", inventory.get("departmentName"));
        metadata.put("itemId", inventory.get("itemId"));
        metadata.put("itemCode", inventory.get("itemCode"));
        metadata.put("itemName", inventory.get("itemName"));
        metadata.put("brand", inventory.get("brand"));
        metadata.put("unit", inventory.get("unit"));
        metadata.put("startDate", start);
        metadata.put("endDate", end);

        var summary = new LinkedHashMap<String, Object>();
        summary.put("openingQuantity", scaleQuantity(timeline.opening().quantity()));
        summary.put("openingUnitCost", money(timeline.opening().unitCost()));
        summary.put("openingCost", money(timeline.opening().totalCost()));
        summary.put("endingQuantity", scaleQuantity(timeline.ending().quantity()));
        summary.put("endingUnitCost", money(timeline.ending().unitCost()));
        summary.put("endingCost", money(timeline.ending().totalCost()));

        var result = new LinkedHashMap<String, Object>();
        result.put("metadata", metadata);
        result.put("records", details.subList(from, to));
        result.put("total", details.size());
        result.put("page", query.page());
        result.put("pageSize", query.pageSize());
        result.put("summary", summary);
        return result;
    }

    public Page<Map<String, Object>> adjustments(CostAdjustmentQuery query) {
        var actor = actor("inventory:read");
        page(query.page(), query.pageSize());
        var args = new ArrayList<Object>(List.of(actor.tenantId()));
        var where = new StringBuilder(" WHERE d.tenant_id=? AND d.doc_type='COST_ADJUST'");
        if (query.departmentId() != null) {
            department(actor, "inventory:read", query.departmentId());
            where.append(" AND d.source_department_id=?");
            args.add(query.departmentId());
        } else {
            where.append(scope(actor, "inventory:read", "d.source_department_id", args));
        }
        if (query.startDate() != null) { where.append(" AND d.document_date>=?"); args.add(query.startDate()); }
        if (query.endDate() != null) { where.append(" AND d.document_date<=?"); args.add(query.endDate()); }
        var search = keyword(query.keyword());
        where.append(" AND (LOWER(d.document_no) LIKE ? ESCAPE '!' OR LOWER(COALESCE(d.remark,'')) LIKE ? ESCAPE '!'"
            + " OR EXISTS (SELECT 1 FROM biz_inventory_cost_adjustment_line cl JOIN biz_item x ON x.tenant_id=cl.tenant_id AND x.id=cl.item_id"
            + " WHERE cl.tenant_id=d.tenant_id AND cl.document_id=d.id AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!')))");
        args.add(search); args.add(search); args.add(search); args.add(search);
        var total = repo.count("SELECT COUNT(*) FROM biz_inventory_doc d" + where, args.toArray());
        args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT d.id,d.document_no,d.document_date,d.operator_name,d.remark,d.status,d.create_time,d.update_time,d.source_department_id,wh.name warehouse_name,COALESCE(u.nickname,u.username) creator_name "
            + "FROM biz_inventory_doc d JOIN sys_department wh ON wh.tenant_id=d.tenant_id AND wh.id=d.source_department_id "
            + "LEFT JOIN sys_user u ON u.tenant_id=d.tenant_id AND u.id=d.creator_id" + where
            + " ORDER BY d.document_date DESC,d.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    @Transactional
    public long saveAdjustment(CostAdjustmentSave input) {
        var actor = write();
        department(actor, "inventory:write", input.departmentId());
        var seen = new HashSet<Long>();
        for (var line : input.lines()) {
            if (!seen.add(line.itemId())) throw new ApiException(400, "同一产品不能重复添加");
            if (repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND id=? AND kind='PRODUCT' AND status=1", actor.tenantId(), line.itemId()) == null) {
                throw new ApiException(400, "成本调整包含不存在或已停用的产品");
            }
        }
        var date = LocalDate.now();
        var number = nextCostAdjustmentNumber(actor.tenantId(), date);
        var documentId = repo.insert("INSERT INTO biz_inventory_doc(tenant_id,doc_type,document_no,source_department_id,target_department_id,document_date,operator_name,status,remark,creator_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), "COST_ADJUST", number, input.departmentId(), null, date,
            blank(input.operatorName()) == null ? "负责人" : blank(input.operatorName()), "CONFIRMED", blank(input.remark()), actor.userId());
        for (var line : input.lines()) {
            var row = repo.one("SELECT id,quantity,cost_price FROM biz_inventory WHERE tenant_id=? AND department_id=? AND item_id=? FOR UPDATE", actor.tenantId(), input.departmentId(), line.itemId());
            var quantity = row == null ? BigDecimal.ZERO : decimal(row, "quantity");
            var previous = row == null ? BigDecimal.ZERO : decimal(row, "costPrice");
            if (row == null) {
                repo.insert("INSERT INTO biz_inventory(tenant_id,department_id,item_id,quantity,cost_price,warning_value,version) VALUES(?,?,?,?,?,?,0)",
                    actor.tenantId(), input.departmentId(), line.itemId(), BigDecimal.ZERO, line.unitCost(), BigDecimal.ZERO);
            } else {
                repo.update("UPDATE biz_inventory SET cost_price=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                    line.unitCost(), actor.tenantId(), id(row, "id"));
            }
            repo.insert("INSERT INTO biz_inventory_cost_adjustment_line(tenant_id,document_id,department_id,item_id,quantity,previous_cost,unit_cost,remark) VALUES(?,?,?,?,?,?,?,?)",
                actor.tenantId(), documentId, input.departmentId(), line.itemId(), quantity, previous, line.unitCost(), blank(line.remark()));
        }
        return documentId;
    }

    private String nextCostAdjustmentNumber(long tenantId, LocalDate date) {
        var prefix = "CA-" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
        var sequence = repo.count("SELECT COUNT(*) FROM biz_inventory_doc WHERE tenant_id=? AND document_no LIKE ?", tenantId, prefix + "%") + 1;
        var number = String.format(Locale.ROOT, "%s%04d", prefix, sequence);
        while (repo.one("SELECT id FROM biz_inventory_doc WHERE tenant_id=? AND document_no=?", tenantId, number) != null) {
            number = String.format(Locale.ROOT, "%s%04d", prefix, ++sequence);
        }
        return number;
    }

    private Timeline timeline(long tenantId, long inventoryId, LocalDate start, LocalDate end, boolean includeDetails) {
        var events = events(tenantId, inventoryId);
        CostState opening = null;
        var state = CostState.empty();
        var inboundQuantity = BigDecimal.ZERO;
        var inboundCost = BigDecimal.ZERO;
        var outboundQuantity = BigDecimal.ZERO;
        var outboundCost = BigDecimal.ZERO;
        var details = new ArrayList<Map<String, Object>>();

        for (var event : events) {
            if (start != null && event.date().isBefore(start)) {
                state = apply(state, event);
                continue;
            }
            if (opening == null) opening = state;
            if (end != null && event.date().isAfter(end)) break;
            var before = state;
            var applied = apply(state, event);
            if (inPeriod(event.date(), start, end)) {
                if ("IN".equals(event.kind())) {
                    inboundQuantity = inboundQuantity.add(event.quantity());
                    inboundCost = inboundCost.add(event.quantity().multiply(event.unitCost()));
                } else if ("OUT".equals(event.kind())) {
                    outboundQuantity = outboundQuantity.add(event.quantity());
                    outboundCost = outboundCost.add(before.unitCost().multiply(event.quantity()));
                }
                if (includeDetails) details.add(detailRow(event, before, applied));
            }
            state = applied;
        }
        if (opening == null) opening = start == null ? CostState.empty() : state;
        // If there was no event at all, the current balance is authoritative.
        if (events.isEmpty()) {
            var row = repo.one("SELECT quantity,cost_price FROM biz_inventory WHERE tenant_id=? AND id=?", tenantId, inventoryId);
            if (row != null) {
                var current = new CostState(decimal(row, "quantity"), decimal(row, "costPrice").multiply(decimal(row, "quantity")));
                opening = current;
                state = current;
            }
        }
        return new Timeline(opening, state, inboundQuantity, inboundCost, outboundQuantity, outboundCost, details);
    }

    private List<CostEvent> events(long tenantId, long inventoryId) {
        var rows = repo.rows("SELECT c.id event_id,c.change_type event_kind,c.quantity,c.unit_cost,c.reference_no,c.reason,c.create_time event_time,doc.document_date,doc.doc_type,ll.actual_quantity target_quantity "
            + "FROM biz_inventory_change c LEFT JOIN biz_inventory_doc doc ON doc.tenant_id=c.tenant_id AND doc.document_no=c.reference_no "
            + "LEFT JOIN biz_inventory_liquidation_line ll ON ll.tenant_id=doc.tenant_id AND ll.document_id=doc.id AND ll.department_id=c.department_id AND ll.item_id=c.item_id "
            + "WHERE c.tenant_id=? AND c.inventory_id=?", tenantId, inventoryId);
        var result = new ArrayList<CostEvent>();
        for (var row : rows) {
            var time = dateTime(row.get("eventTime"));
            result.add(new CostEvent(id(row, "eventId"), eventDate(row), time, text(row, "eventKind"), decimal(row, "quantity"), decimal(row, "unitCost"),
                row.get("targetQuantity") == null ? null : decimal(row, "targetQuantity"), blank(text(row, "referenceNo")), blank(text(row, "reason"))));
        }
        var adjustments = repo.rows("SELECT l.id event_id,l.quantity,l.unit_cost,l.remark,d.document_no,d.document_date,d.create_time event_time "
            + "FROM biz_inventory_cost_adjustment_line l JOIN biz_inventory_doc d ON d.tenant_id=l.tenant_id AND d.id=l.document_id "
            + "WHERE l.tenant_id=? AND l.department_id=(SELECT department_id FROM biz_inventory WHERE tenant_id=? AND id=?) AND l.item_id=(SELECT item_id FROM biz_inventory WHERE tenant_id=? AND id=?) AND d.status='CONFIRMED'",
            tenantId, tenantId, inventoryId, tenantId, inventoryId);
        for (var row : adjustments) {
            var time = dateTime(row.get("eventTime"));
            result.add(new CostEvent(id(row, "eventId"), eventDate(row), time, "COST_ADJUST", decimal(row, "quantity"), decimal(row, "unitCost"), null,
                blank(text(row, "documentNo")), blank(text(row, "remark"))));
        }
        result.sort(Comparator.comparing(CostEvent::date).thenComparing(CostEvent::time).thenComparingLong(CostEvent::id));
        return result;
    }

    private static CostState apply(CostState state, CostEvent event) {
        if ("IN".equals(event.kind())) {
            var cost = event.quantity().multiply(event.unitCost());
            return new CostState(state.quantity().add(event.quantity()), state.totalCost().add(cost));
        }
        if ("OUT".equals(event.kind())) {
            var quantity = state.quantity().subtract(event.quantity());
            var cost = state.unitCost().multiply(event.quantity());
            return new CostState(quantity.max(BigDecimal.ZERO), state.totalCost().subtract(cost).max(BigDecimal.ZERO));
        }
        if ("COST_ADJUST".equals(event.kind())) {
            return new CostState(state.quantity(), state.quantity().multiply(event.unitCost()));
        }
        if (event.targetQuantity() != null) {
            return new CostState(event.targetQuantity(), event.targetQuantity().multiply(state.unitCost()));
        }
        return state;
    }

    private static Map<String, Object> detailRow(CostEvent event, CostState before, CostState after) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", event.id());
        row.put("documentNo", event.documentNo());
        row.put("occurredAt", event.date() + (event.time() == null ? "" : " " + event.time().toLocalTime().withNano(0)));
        row.put("type", typeLabel(event));
        row.put("inboundQuantity", "IN".equals(event.kind()) ? scaleQuantity(event.quantity()) : BigDecimal.ZERO.setScale(3));
        row.put("inboundUnitCost", "IN".equals(event.kind()) ? money(event.unitCost()) : money(BigDecimal.ZERO));
        row.put("inboundCost", "IN".equals(event.kind()) ? money(event.quantity().multiply(event.unitCost())) : money(BigDecimal.ZERO));
        row.put("outboundQuantity", "OUT".equals(event.kind()) ? scaleQuantity(event.quantity()) : BigDecimal.ZERO.setScale(3));
        row.put("outboundUnitCost", "OUT".equals(event.kind()) ? money(before.unitCost()) : money(BigDecimal.ZERO));
        row.put("outboundCost", "OUT".equals(event.kind()) ? money(event.quantity().multiply(before.unitCost())) : money(BigDecimal.ZERO));
        row.put("balanceQuantity", scaleQuantity(after.quantity()));
        row.put("balanceUnitCost", money(after.unitCost()));
        row.put("balanceCost", money(after.totalCost()));
        row.put("remark", event.remark());
        return row;
    }

    private static String typeLabel(CostEvent event) {
        if ("COST_ADJUST".equals(event.kind())) return "成本调整";
        if ("ADJUST".equals(event.kind())) return "盘点调整";
        if (event.documentNo() != null && event.documentNo().startsWith("DB-")) return "入库-调拨";
        if (event.documentNo() != null && event.documentNo().startsWith("DO-")) return "出库-调拨";
        return "IN".equals(event.kind()) ? "入库" : "出库";
    }

    private static boolean inPeriod(LocalDate date, LocalDate start, LocalDate end) {
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
    }

    private static Map<String, Object> summaryZero() {
        var result = new LinkedHashMap<String, Object>();
        result.put("openingCost", BigDecimal.ZERO); result.put("inboundCost", BigDecimal.ZERO);
        result.put("outboundCost", BigDecimal.ZERO); result.put("endingCost", BigDecimal.ZERO);
        return result;
    }

    private static void add(Map<String, Object> map, String key, BigDecimal value) {
        map.put(key, ((BigDecimal) map.get(key)).add(value));
    }

    private static BigDecimal average(BigDecimal total, BigDecimal quantity) {
        return quantity.signum() == 0 ? BigDecimal.ZERO : total.divide(quantity, 8, RoundingMode.HALF_UP);
    }

    private static void decorateAccountingRow(Map<String, Object> row, Timeline timeline) {
        row.put("openingQuantity", scaleQuantity(timeline.opening().quantity()));
        row.put("openingCost", money(timeline.opening().totalCost()));
        row.put("openingUnitCost", money(timeline.opening().unitCost()));
        row.put("inboundQuantity", scaleQuantity(timeline.inboundQuantity()));
        row.put("inboundCost", money(timeline.inboundCost()));
        row.put("inboundUnitCost", money(average(timeline.inboundCost(), timeline.inboundQuantity())));
        row.put("outboundQuantity", scaleQuantity(timeline.outboundQuantity()));
        row.put("outboundCost", money(timeline.outboundCost()));
        row.put("outboundUnitCost", money(average(timeline.outboundCost(), timeline.outboundQuantity())));
        row.put("endingQuantity", scaleQuantity(timeline.ending().quantity()));
        row.put("endingCost", money(timeline.ending().totalCost()));
        row.put("endingUnitCost", money(timeline.ending().unitCost()));
        row.remove("inventoryQuantity");
        row.remove("inventoryCostPrice");
    }

    private static BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal scaleQuantity(BigDecimal value) { return value == null ? BigDecimal.ZERO.setScale(3) : value.setScale(3, RoundingMode.HALF_UP); }
    private static BigDecimal decimal(Map<String, Object> row, String name) { return new BigDecimal(Objects.toString(row.get(name), "0")); }
    private static String text(Map<String, Object> row, String name) { return Objects.toString(row.get(name), ""); }

    private static LocalDate parse(LocalDate value) { return value; }
    private static LocalDate parse(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value); } catch (Exception e) { throw new ApiException(400, "日期格式不正确"); } }
    private static LocalDate eventDate(Map<String, Object> row) {
        var documentDate = row.get("documentDate");
        if (documentDate instanceof java.sql.Date date) return date.toLocalDate();
        if (documentDate instanceof LocalDate date) return date;
        if (documentDate != null) return LocalDate.parse(documentDate.toString());
        return dateTime(row.get("eventTime")).toLocalDate();
    }
    private static LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay();
        if (value == null) return LocalDateTime.MIN;
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
