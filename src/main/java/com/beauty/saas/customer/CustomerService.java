package com.beauty.saas.customer;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.CustomerRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

/** Tenant-scoped customer dossiers with a read-only all-tenant platform view. */
@Service
@RequiredArgsConstructor
public class CustomerService {
    private static final String COLUMNS = "c.id,c.tenant_id,t.name tenant_name,t.code tenant_code,c.code,c.name,c.phone,c.level,c.source,c.birthday,c.birthday_type,c.gender,c.join_date,c.avatar_url,c.referrer,c.initial_spent,c.referral_date,c.remark,c.tracker,c.adviser,c.store_id,COALESCE(d.name,'当前门店') store_name,c.card_count,c.balance,c.spent,c.visit_count,c.last_visit,c.version,c.create_time,c.update_time";
    private static final String CUSTOMER_FROM = " FROM biz_customer c JOIN sys_tenant t ON t.id=c.tenant_id LEFT JOIN sys_department d ON d.tenant_id=c.tenant_id AND d.id=c.store_id";
    private final IamRepository repo;
    private final AccessService access;

    private AccountPrincipal reader() {
        var actor = access.currentTenantOrPlatform();
        actor.require("customers:read");
        return actor;
    }

    private AccountPrincipal writer() {
        var actor = access.currentTenant();
        actor.require("customers:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", actor.tenantId()) == null)
            throw new ApiException(409, "企业已停用，请先由平台启用");
        var fresh = access.reload(actor);
        fresh.require("customers:write");
        return fresh;
    }

    private static void page(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new ApiException(400, "分页参数不正确，每页最多 100 条");
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }
    private static String keyword(String value) {
        var input = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        if (input.length() > 100) throw new ApiException(400, "搜索内容不能超过 100 字");
        return "%" + input.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public Page<Map<String, Object>> list(CustomerQuery query) {
        var actor = reader();
        page(query.page(), query.pageSize());
        var args = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE c.deleted=0");
        if (actor.tenantId() > 0) { where.append(" AND c.tenant_id=?"); args.add(actor.tenantId()); }
        applyScope(actor, query.storeId(), where, args);
        if (query.storeId() != null) {
            actor.requireDepartment("customers:read", query.storeId());
            where.append(" AND c.store_id=?");
            args.add(query.storeId());
        }
        if (query.source() != null && !query.source().isBlank()) { where.append(" AND c.source=?"); args.add(query.source().trim()); }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            var value = keyword(query.keyword());
            where.append(" AND (LOWER(c.name) LIKE ? ESCAPE '!' OR c.phone LIKE ? ESCAPE '!' OR LOWER(c.code) LIKE ? ESCAPE '!')");
            args.add(value); args.add(value); args.add(value);
        }
        var total = repo.count("SELECT COUNT(*)" + CUSTOMER_FROM + where, args.toArray());
        args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT " + COLUMNS + CUSTOMER_FROM + where + " ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    public Map<String, Object> detail(long id) {
        var actor = reader();
        var args = new ArrayList<Object>(List.of(id));
        var where = new StringBuilder(" WHERE c.id=? AND c.deleted=0");
        if (actor.tenantId() > 0) { where.append(" AND c.tenant_id=?"); args.add(actor.tenantId()); }
        applyScope(actor, null, where, args);
        var row = repo.one("SELECT " + COLUMNS + CUSTOMER_FROM + where, args.toArray());
        if (row == null) throw new ApiException(404, "顾客不存在或不在可访问范围内");
        return row;
    }

    @Transactional
    public long save(Long id, CustomerSave input) {
        var actor = writer();
        var storeId = resolveStore(actor, input.storeId());
        var name = input.name().trim();
        var phone = input.phone().trim();
        var code = text(input.code());
        if (id == null && code == null) code = nextCode(actor.tenantId());
        if (id != null) {
            var existing = repo.one("SELECT * FROM biz_customer WHERE tenant_id=? AND id=? AND deleted=0 FOR UPDATE", actor.tenantId(), id);
            if (existing == null) throw new ApiException(404, "顾客不存在");
            if (existing.get("storeId") != null) actor.requireDepartment("customers:write", id(existing, "storeId"));
            var effectiveCode = code == null ? Objects.toString(existing.get("code"), null) : code;
            repo.update("UPDATE biz_customer SET code=?,name=?,phone=?,level=?,source=?,birthday=?,birthday_type=?,gender=?,join_date=?,avatar_url=?,referrer=?,initial_spent=?,referral_date=?,remark=?,tracker=?,adviser=?,store_id=?,card_count=?,balance=?,spent=?,visit_count=?,last_visit=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                effectiveCode, name, phone, text(input.level()) == null ? "无等级" : input.level().trim(), text(input.source()), input.birthday(), text(input.birthdayType()) == null ? "阳历" : input.birthdayType().trim(), text(input.gender()), input.joinDate(), text(input.avatarUrl()), text(input.referrer()), decimal(input.initialSpent(), existing.get("initialSpent")), input.referralDate(), text(input.remark()), text(input.tracker()), text(input.adviser()), storeId,
                input.cardCount() == null ? id(existing, "cardCount") : input.cardCount(), decimal(input.balance(), existing.get("balance")), decimal(input.spent(), existing.get("spent")), input.visitCount() == null ? id(existing, "visitCount") : input.visitCount(), text(input.lastVisit()), actor.tenantId(), id);
            return id;
        }
        if (repo.count("SELECT COUNT(*) FROM biz_customer WHERE tenant_id=? AND code=?", actor.tenantId(), code) > 0) throw new ApiException(409, "顾客编号已存在");
        return repo.insert("INSERT INTO biz_customer(tenant_id,code,name,phone,level,source,birthday,birthday_type,gender,join_date,avatar_url,referrer,initial_spent,referral_date,remark,tracker,adviser,store_id,card_count,balance,spent,visit_count,last_visit) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), code, name, phone, text(input.level()) == null ? "无等级" : input.level().trim(), text(input.source()), input.birthday(), text(input.birthdayType()) == null ? "阳历" : input.birthdayType().trim(), text(input.gender()), input.joinDate(), text(input.avatarUrl()), text(input.referrer()), decimal(input.initialSpent(), BigDecimal.ZERO), input.referralDate(), text(input.remark()), text(input.tracker()), text(input.adviser()), storeId,
            input.cardCount() == null ? 0 : input.cardCount(), decimal(input.balance(), BigDecimal.ZERO), decimal(input.spent(), BigDecimal.ZERO), input.visitCount() == null ? 0 : input.visitCount(), text(input.lastVisit()));
    }

    @Transactional
    public void delete(long id) {
        var actor = writer();
        var row = repo.one("SELECT store_id FROM biz_customer WHERE tenant_id=? AND id=? AND deleted=0 FOR UPDATE", actor.tenantId(), id);
        if (row == null) throw new ApiException(404, "顾客不存在");
        if (row.get("storeId") != null) actor.requireDepartment("customers:write", id(row, "storeId"));
        if (repo.update("UPDATE biz_customer SET deleted=1,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?", actor.tenantId(), id) == 0)
            throw new ApiException(404, "顾客不存在");
    }

    public Page<Map<String, Object>> storages(StorageQuery query) {
        var actor = reader();
        page(query.page(), query.pageSize());
        var args = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE c.deleted=0");
        if (actor.tenantId() > 0) { where.append(" AND s.tenant_id=?"); args.add(actor.tenantId()); }
        if (!actor.global("customers:read")) {
            var allowed = actor.scopes().getOrDefault("customers:read", Set.of()).stream().filter(value -> value > 0).toList();
            if (allowed.isEmpty()) where.append(" AND 1=0");
            else { where.append(" AND c.store_id IN (").append(marks(allowed.size())).append(")"); args.addAll(allowed); }
        }
        if (query.storeId() != null) { actor.requireDepartment("customers:read", query.storeId()); where.append(" AND s.store_id=?"); args.add(query.storeId()); }
        if (query.storageType() != null && !query.storageType().isBlank()) { where.append(" AND s.storage_type=?"); args.add(query.storageType().trim()); }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            var value = keyword(query.keyword());
            where.append(" AND (LOWER(c.name) LIKE ? ESCAPE '!' OR c.phone LIKE ? ESCAPE '!' OR LOWER(c.code) LIKE ? ESCAPE '!' OR LOWER(s.item_name) LIKE ? ESCAPE '!')");
            args.add(value); args.add(value); args.add(value); args.add(value);
        }
        var from = " FROM biz_customer_storage s JOIN biz_customer c ON c.tenant_id=s.tenant_id AND c.id=s.customer_id JOIN sys_tenant t ON t.id=s.tenant_id LEFT JOIN sys_department d ON d.tenant_id=s.tenant_id AND d.id=s.store_id";
        var total = repo.count("SELECT COUNT(*)" + from + where, args.toArray());
        args.add(query.pageSize()); args.add((query.page() - 1) * query.pageSize());
        var rows = repo.rows("SELECT s.id,s.tenant_id,t.name tenant_name,s.customer_id,c.name customer_name,c.phone,c.code customer_code,s.store_id,COALESCE(d.name,'当前门店') store_name,s.storage_type,s.item_name,s.quantity,s.remark,s.create_time" + from + where + " ORDER BY s.create_time DESC,s.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page<>(rows, total, query.page(), query.pageSize());
    }

    @Transactional
    public long saveStorage(StorageSave input) {
        var actor = writer();
        var customer = repo.one("SELECT id,store_id FROM biz_customer WHERE tenant_id=? AND id=? AND deleted=0 FOR UPDATE", actor.tenantId(), input.customerId());
        if (customer == null) throw new ApiException(404, "顾客不存在");
        if (customer.get("storeId") != null) actor.requireDepartment("customers:write", id(customer, "storeId"));
        Long requestedStoreId = input.storeId() != null ? input.storeId() : (customer.get("storeId") == null ? resolveStore(actor, null) : Long.valueOf(id(customer, "storeId")));
        if (requestedStoreId == null) throw new ApiException(400, "请先配置可用门店");
        var store = repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), requestedStoreId);
        if (store == null) throw new ApiException(404, "门店不存在或已停用");
        actor.requireDepartment("customers:write", requestedStoreId);
        return repo.insert("INSERT INTO biz_customer_storage(tenant_id,customer_id,store_id,storage_type,item_name,quantity,remark) VALUES(?,?,?,?,?,?,?)", actor.tenantId(), input.customerId(), requestedStoreId, input.storageType(), input.itemName().trim(), input.quantity(), text(input.remark()));
    }

    private void applyScope(AccountPrincipal actor, Long storeId, StringBuilder where, List<Object> args) {
        if (actor.global("customers:read")) return;
        var allowed = actor.scopes().getOrDefault("customers:read", Set.of()).stream().filter(id -> id > 0).toList();
        if (allowed.isEmpty()) where.append(" AND 1=0");
        else if (storeId == null) { where.append(" AND c.store_id IN (").append(marks(allowed.size())).append(")"); args.addAll(allowed); }
    }

    private Long resolveStore(AccountPrincipal actor, Long requested) {
        if (requested != null) {
            var store = repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1", actor.tenantId(), requested);
            if (store == null) throw new ApiException(404, "门店不存在或已停用");
            actor.requireDepartment("customers:write", requested);
            return requested;
        }
        if (actor.global("customers:write")) {
            var first = repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND type='STORE' AND status=1 ORDER BY id LIMIT 1", actor.tenantId());
            return first == null ? null : id(first, "id");
        }
        return actor.scopes().getOrDefault("customers:write", Set.of()).stream().filter(value -> value > 0).findFirst().orElse(null);
    }

    private String nextCode(long tenantId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            var code = "CUS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            if (repo.count("SELECT COUNT(*) FROM biz_customer WHERE tenant_id=? AND code=?", tenantId, code) == 0) return code;
        }
        throw new ApiException(409, "顾客编号生成失败，请重试");
    }

    private static BigDecimal decimal(BigDecimal value, Object fallback) { return value != null ? value : new BigDecimal(Objects.toString(fallback, "0")); }
}
