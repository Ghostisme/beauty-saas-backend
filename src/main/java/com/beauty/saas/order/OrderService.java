package com.beauty.saas.order;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.OrderRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import com.beauty.saas.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final IamRepository repo;
    private final AccessService access;
    private final HttpServletRequest request;
    private static final String VERIFIED="(o.reviewed_at IS NOT NULL AND (COALESCE(v.recheck_after_performance_change,1)=0 OR COALESCE(o.reviewed_performance_version=o.performance_version,FALSE)))";
    private static final String FROM=" FROM biz_order o JOIN sys_tenant t ON t.id=o.tenant_id LEFT JOIN sys_department d ON d.tenant_id=o.tenant_id AND d.id=o.department_id LEFT JOIN biz_order_verification_setting v ON v.tenant_id=o.tenant_id";
    private static final String COLUMNS="o.id,o.tenant_id,o.department_id store_id,o.order_no,o.status,o.consumption_type,o.customer_name,o.customer_phone,o.customer_code,o.order_time,o.total_amount,o.paid_amount,o.version,o.remark,o.reviewed_at,COALESCE(NULLIF(o.store_name,''),d.name) store_name,t.code tenant_code,t.name tenant_name,t.status tenant_status,COALESCE(v.enabled,1) verification_enabled,CASE WHEN "+VERIFIED+" THEN 1 ELSE 0 END verified";

    private AccountPrincipal reader() {
        var actor=access.current();
        // Only platform read operations may omit the target enterprise for an aggregate view.
        if (!actor.platformAdmin() || request.getHeader("X-Tenant-Id")!=null) actor=access.currentTenant();
        actor.require("orders:read");
        return actor;
    }
    private String scope(AccountPrincipal actor,List<Object> args,String tenantColumn,String departmentColumn) {
        String result="";
        if (!actor.platformAdmin() || actor.tenantId()>0) { result+=" AND "+tenantColumn+"=?"; args.add(actor.tenantId()); }
        if (!actor.global("orders:read")) {
            var departments=actor.scopes().getOrDefault("orders:read",Set.of());
            if (departments.isEmpty()) return result+" AND 1=0";
            result+=" AND "+departmentColumn+" IN ("+marks(departments.size())+")"; args.addAll(departments);
        }
        return result;
    }
    private static String marks(int size) { return String.join(",",Collections.nCopies(size,"?")); }
    private static void page(int page,int size) {
        if (page<1 || page>100000 || size<1 || size>100) throw new ApiException(400,"分页参数不正确，每页最多 100 条");
    }
    private static String keyword(String value) {
        String input=Objects.toString(value,"").trim().toLowerCase(Locale.ROOT);
        if (input.length()>100) throw new ApiException(400,"搜索内容不能超过 100 字");
        return "%"+input.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    private static boolean present(String value) { return value!=null && !value.isBlank(); }

    public Map<String,Object> options() {
        reader();
        return Map.of("consumptionTypes",OrderCatalog.CONSUMPTION_TYPES,"paymentMethods",OrderCatalog.PAYMENT_FILTERS);
    }
    public Page<Map<String,Object>> stores(String search,int page,int size) {
        var actor=reader(); page(page,size);
        List<Object> args=new ArrayList<>();
        String where=" WHERE d.type='STORE'"+scope(actor,args,"d.tenant_id","d.id");
        where+=" AND (LOWER(d.name) LIKE ? ESCAPE '!' OR LOWER(d.code) LIKE ? ESCAPE '!')";
        args.add(keyword(search)); args.add(keyword(search));
        String from=" FROM sys_department d JOIN sys_tenant t ON t.id=d.tenant_id";
        long total=repo.count("SELECT COUNT(*)"+from+where,args.toArray());
        args.add(size); args.add((page-1)*size);
        return new Page<>(repo.rows("SELECT d.id,d.name,d.code,d.status,d.tenant_id,t.name tenant_name,t.code tenant_code"+from+where+" ORDER BY d.sort_order,d.id LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }

    public Page<Map<String,Object>> list(Query query) {
        var actor=reader(); validate(query);
        List<Object> args=new ArrayList<>();
        String where=" WHERE o.status=?";
        args.add(query.tab().equals("pending")?"PENDING":"CONFIRMED");
        where+=scope(actor,args,"o.tenant_id","o.department_id");
        if (query.tab().equals("balance")) where+=" AND o.total_amount>o.paid_amount";
        if (query.storeId()!=null) {
            var store=repo.one("SELECT tenant_id FROM sys_department WHERE id=? AND type='STORE'",query.storeId());
            if (store==null || (actor.tenantId()>0 && id(store,"tenantId")!=actor.tenantId()) || !actor.can("orders:read",query.storeId()))
                throw new ApiException(404,"门店不存在或不在可访问范围内");
            where+=" AND o.department_id=?"; args.add(query.storeId());
        }
        if (query.startDate()!=null) { where+=" AND o.order_time>=? AND o.order_time<?"; args.add(query.startDate().atStartOfDay()); args.add(query.endDate().plusDays(1).atStartOfDay()); }
        if (present(query.keyword())) {
            String value=keyword(query.keyword());
            switch (query.searchType()) {
                case "ORDER" -> { where+=" AND LOWER(o.order_no) LIKE ? ESCAPE '!'"; args.add(value); }
                case "STAFF" -> {
                    where+=" AND EXISTS(SELECT 1 FROM biz_order_staff s WHERE s.tenant_id=o.tenant_id AND s.order_id=o.id AND (LOWER(s.staff_name) LIKE ? ESCAPE '!' OR s.staff_phone LIKE ? ESCAPE '!' OR LOWER(s.staff_code) LIKE ? ESCAPE '!'))";
                    args.addAll(List.of(value,value,value));
                }
                default -> {
                    where+=" AND (LOWER(o.customer_name) LIKE ? ESCAPE '!' OR o.customer_phone LIKE ? ESCAPE '!' OR LOWER(o.customer_code) LIKE ? ESCAPE '!')";
                    args.addAll(List.of(value,value,value));
                }
            }
        }
        if (present(query.consumptionType())) {
            where+=" AND (o.consumption_type=? OR EXISTS(SELECT 1 FROM biz_order_item i WHERE i.tenant_id=o.tenant_id AND i.order_id=o.id AND i.consumption_type=?))";
            args.add(query.consumptionType()); args.add(query.consumptionType());
        }
        if (present(query.paymentMethod())) {
            String column=OrderCatalog.PAYMENT_CATEGORIES.contains(query.paymentMethod())?"category":"method";
            where+=" AND EXISTS(SELECT 1 FROM biz_order_payment p WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p."+column+"=?)";
            args.add(query.paymentMethod());
        }
        if (present(query.verification())) where+=" AND "+(query.verification().equals("VERIFIED")?"":"NOT ")+VERIFIED;
        long total=repo.count("SELECT COUNT(*)"+FROM+where,args.toArray());
        String sort=query.sortBy().equals("orderNo")?"o.order_no":"o.order_time";
        String direction=query.sortDirection().equals("asc")?" ASC":" DESC";
        args.add(query.pageSize()); args.add((query.page()-1)*query.pageSize());
        var rows=repo.rows("SELECT "+COLUMNS+FROM+where+" ORDER BY "+sort+direction+",o.id"+direction+" LIMIT ? OFFSET ?",args.toArray());
        enrich(rows,actor,false);
        return new Page<>(rows,total,query.page(),query.pageSize());
    }
    private void validate(Query query) {
        page(query.page(),query.pageSize()); keyword(query.keyword());
        if (!Set.of("all","pending","balance").contains(query.tab()) || !Set.of("CUSTOMER","ORDER","STAFF").contains(query.searchType())) throw new ApiException(400,"订单页签或搜索类型不正确");
        if (!Set.of("orderNo","orderTime").contains(query.sortBy()) || !Set.of("asc","desc").contains(query.sortDirection())) throw new ApiException(400,"排序参数不正确");
        if ((query.startDate()==null)!=(query.endDate()==null)) throw new ApiException(400,"请完整选择起止日期");
        if (query.startDate()!=null && (query.startDate().isAfter(query.endDate()) || query.startDate().getYear()<1900 || query.endDate().getYear()>9998)) throw new ApiException(400,"日期范围不正确");
        if (query.storeId()!=null && query.storeId()<=0) throw new ApiException(400,"门店参数不正确");
        OrderCatalog.validate(OrderCatalog.CONSUMPTION_TYPES,query.consumptionType(),"消费类型");
        OrderCatalog.validate(OrderCatalog.PAYMENT_FILTERS,query.paymentMethod(),"支付方式");
        if (present(query.verification()) && !Set.of("VERIFIED","UNVERIFIED").contains(query.verification())) throw new ApiException(400,"核对状态不正确");
    }

    public Map<String,Object> detail(long order) {
        var actor=reader();
        List<Object> args=new ArrayList<>(List.of(order));
        var row=repo.one("SELECT "+COLUMNS+FROM+" WHERE o.id=?"+scope(actor,args,"o.tenant_id","o.department_id"),args.toArray());
        if (row==null) throw new ApiException(404,"订单不存在或不在可访问范围内");
        enrich(List.of(row),actor,true);
        return row;
    }
    private void enrich(List<Map<String,Object>> rows,AccountPrincipal actor,boolean detail) {
        if (rows.isEmpty()) return;
        Object[] ids=rows.stream().map(row -> id(row,"id")).toArray();
        String where=" WHERE x.order_id IN ("+marks(ids.length)+")";
        var items=repo.rows("SELECT x.* FROM biz_order_item x JOIN biz_order o ON o.id=x.order_id AND o.tenant_id=x.tenant_id"+where+" ORDER BY x.id",ids);
        var staff=repo.rows("SELECT x.* FROM biz_order_staff x JOIN biz_order o ON o.id=x.order_id AND o.tenant_id=x.tenant_id"+where+" ORDER BY x.id",ids);
        var payments=detail?repo.rows("SELECT x.* FROM biz_order_payment x JOIN biz_order o ON o.id=x.order_id AND o.tenant_id=x.tenant_id"+where+" ORDER BY x.id",ids):List.<Map<String,Object>>of();
        for (var item:items) for (String field:List.of("quantity","unitPrice","amount")) item.put(field,money(item.get(field)));
        for (var payment:payments) payment.put("amount",money(payment.get("amount")));
        for (var row:rows) {
            long order=id(row,"id");
            BigDecimal total=new BigDecimal(row.get("totalAmount").toString()), paid=new BigDecimal(row.get("paidAmount").toString());
            row.put("totalAmount",money(total)); row.put("paidAmount",money(paid)); row.put("outstandingAmount",money(total.subtract(paid).max(BigDecimal.ZERO)));
            row.put("verified",id(row,"verified")==1); row.put("verificationEnabled",id(row,"verificationEnabled")==1);
            row.put("canVerify",actor.can("orders:write",id(row,"storeId")) && id(row,"tenantStatus")==1 && text(row,"status").equals("CONFIRMED") && Boolean.TRUE.equals(row.get("verificationEnabled")));
            row.put("items",items.stream().filter(item -> id(item,"orderId")==order).toList());
            row.put("staff",staff.stream().filter(item -> id(item,"orderId")==order).toList());
            if (detail) row.put("payments",payments);
        }
    }
    private static String money(Object amount) { return new BigDecimal(amount.toString()).setScale(2).toPlainString(); }

    public Map<String,Object> settings() {
        var actor=access.currentTenant(); actor.requireGlobal("order-settings:read");
        return setting(actor.tenantId());
    }
    private Map<String,Object> setting(long tenant) {
        var row=repo.one("SELECT enabled,recheck_after_performance_change,version FROM biz_order_verification_setting WHERE tenant_id=?",tenant);
        return row==null?Map.of("enabled",true,"recheckAfterPerformanceChange",true,"version",0L)
            :Map.of("enabled",id(row,"enabled")==1,"recheckAfterPerformanceChange",id(row,"recheckAfterPerformanceChange")==1,"version",id(row,"version"));
    }
    private AccountPrincipal writer(String permission) {
        var actor=access.currentTenant(); actor.require(permission);
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",actor.tenantId())==null) throw new ApiException(409,"企业已停用，请先由平台启用");
        actor=access.reload(actor); actor.require(permission);
        return actor;
    }
    @Transactional public Map<String,Object> saveSettings(VerificationSetting input) {
        var actor=writer("order-settings:write"); actor.requireGlobal("order-settings:write");
        var old=setting(actor.tenantId());
        if (id(old,"version")!=input.version()) throw new ApiException(409,"设置已被其他人更新，请重新加载后保存");
        if (repo.count("SELECT COUNT(*) FROM biz_order_verification_setting WHERE tenant_id=?",actor.tenantId())==0)
            repo.update("INSERT INTO biz_order_verification_setting(tenant_id,enabled,recheck_after_performance_change,version,updated_by) VALUES(?,?,?,?,?)",actor.tenantId(),input.enabled()?1:0,input.recheckAfterPerformanceChange()?1:0,1,actor.userId());
        else repo.update("UPDATE biz_order_verification_setting SET enabled=?,recheck_after_performance_change=?,version=version+1,updated_by=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=?",input.enabled()?1:0,input.recheckAfterPerformanceChange()?1:0,actor.userId(),actor.tenantId());
        audit(actor,null,"ORDER_VERIFICATION_SETTINGS","订单核对"+(input.enabled()?"启用":"禁用")+"；业绩重分配后重新核对="+input.recheckAfterPerformanceChange());
        return setting(actor.tenantId());
    }
    @Transactional public void verify(long order,Verify input) {
        var actor=writer("orders:write");
        var row=repo.one("SELECT * FROM biz_order WHERE tenant_id=? AND id=? FOR UPDATE",actor.tenantId(),order);
        if (row==null || !actor.can("orders:read",id(row,"departmentId"))) throw new ApiException(404,"订单不存在或不在可访问范围内");
        actor.requireDepartment("orders:write",id(row,"departmentId"));
        if (!text(row,"status").equals("CONFIRMED")) throw new ApiException(409,"未完成订单不能核对");
        if (!Boolean.TRUE.equals(setting(actor.tenantId()).get("enabled"))) throw new ApiException(409,"该企业已禁用订单核对");
        if (id(row,"version")!=input.version()) throw new ApiException(409,"订单已更新，请刷新后重新核对");
        repo.update("UPDATE biz_order SET reviewed_at=?,reviewed_by=?,reviewed_performance_version=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
            input.verified()?LocalDateTime.now():null,input.verified()?actor.userId():null,input.verified()?id(row,"performanceVersion"):null,actor.tenantId(),order);
        audit(actor,order,input.verified()?"VERIFY_ORDER":"UNVERIFY_ORDER",input.verified()?"核对订单":"取消订单核对");
    }
    private void audit(AccountPrincipal actor,Long order,String action,String detail) {
        repo.update("INSERT INTO biz_order_audit(tenant_id,order_id,actor_id,action,detail) VALUES(?,?,?,?,?)",actor.tenantId(),order,actor.userId(),action,detail);
        if (actor.platformAdmin()) repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,?,?)",actor.userId(),actor.tenantId(),action,detail);
    }
}
