package com.beauty.saas.catalog;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.ItemRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

/** Tenant-scoped catalog, stock ledger and commission scheme operations. */
@Service
@RequiredArgsConstructor
public class CatalogService {
    private final IamRepository repo;
    private final AccessService access;
    private AccountPrincipal actor(String permission) { var actor=access.currentTenant(); actor.require(permission); return actor; }
    private AccountPrincipal write(String permission) {
        var actor=actor(permission);
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",actor.tenantId())==null) throw new ApiException(409,"企业已停用，请先由平台启用");
        var fresh=access.reload(actor); fresh.require(permission); return fresh;
    }
    private static void page(int page,int size) { if(page<1 || page>100000 || size<1 || size>100) throw new ApiException(400,"分页参数不正确，每页最多 100 条"); }
    private static String keyword(String input) { String value=Objects.toString(input,"").trim().toLowerCase(Locale.ROOT); if(value.length()>100) throw new ApiException(400,"搜索内容不能超过 100 字"); return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private static String marks(int n) { return String.join(",",Collections.nCopies(n,"?")); }
    private static String departmentScope(AccountPrincipal actor, String permission, String column, List<Object> args) {
        if (actor.global(permission)) return "";
        Set<Long> allowed = actor.scopes().getOrDefault(permission, Set.of()).stream().filter(id -> id > 0).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) return " AND 1=0";
        args.addAll(allowed);
        return " AND " + column + " IN (" + marks(allowed.size()) + ")";
    }
    private static String kind(String value) { String k=Objects.toString(value,"").toUpperCase(Locale.ROOT); if(!Set.of("PROJECT","PRODUCT","CARD").contains(k)) throw new ApiException(400,"品项类型不正确"); return k; }
    private static String commissionKind(String value) { String k=Objects.toString(value,"").toUpperCase(Locale.ROOT); if(!Set.of("PROJECT","PRODUCT","CARD","STEP").contains(k)) throw new ApiException(400,"提成类型不正确"); return k; }

    public Page<Map<String,Object>> items(String type,int page,int size,String search,Integer status) {
        var actor=actor("items:read"); page(page,size); String k=kind(type); List<Object> args=new ArrayList<>(List.of(actor.tenantId(),k,keyword(search)));
        String where=" WHERE tenant_id=? AND kind=? AND (LOWER(code) LIKE ? ESCAPE '!' OR LOWER(name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(category,'')) LIKE ? ESCAPE '!')"; args.add(keyword(search)); args.add(keyword(search));
        if(status!=null){if(status!=0&&status!=1) throw new ApiException(400,"品项状态不正确"); where+=" AND status=?"; args.add(status);}
        long total=repo.count("SELECT COUNT(*) FROM biz_item"+where,args.toArray()); args.add(size);args.add((page-1)*size);
        return new Page<>(repo.rows("SELECT * FROM biz_item"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }
    @Transactional public long saveItem(Long id,ItemSave input,String type) {
        var actor=write("items:write"); String k=kind(type); String code=input.code().trim();
        Map<String,Object> duplicate = id == null
            ? repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND kind=? AND code=?",actor.tenantId(),k,code)
            : repo.one("SELECT id FROM biz_item WHERE tenant_id=? AND kind=? AND code=? AND id<>?",actor.tenantId(),k,code,id);
        if (duplicate != null) throw new ApiException(409,"同类型品项编码已存在");
        if(id==null) return repo.insert("INSERT INTO biz_item(tenant_id,kind,code,name,category,price,duration_minutes,unit,spec,description,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),k,code,input.name().trim(),blank(input.category()),input.price(),input.durationMinutes(),blank(input.unit()),blank(input.spec()),blank(input.description()),input.status());
        var old=repo.one("SELECT * FROM biz_item WHERE tenant_id=? AND id=? AND kind=? FOR UPDATE",actor.tenantId(),id,k); if(old==null) throw new ApiException(404,"品项不存在");
        repo.update("UPDATE biz_item SET code=?,name=?,category=?,price=?,duration_minutes=?,unit=?,spec=?,description=?,status=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",code,input.name().trim(),blank(input.category()),input.price(),input.durationMinutes(),blank(input.unit()),blank(input.spec()),blank(input.description()),input.status(),actor.tenantId(),id); return id;
    }
    @Transactional public void deleteItem(long id,String type) { var actor=write("items:write"); String k=kind(type); if(repo.update("UPDATE biz_item SET status=0,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND kind=?",actor.tenantId(),id,k)==0) throw new ApiException(404,"品项不存在"); }
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}

    public Page<Map<String,Object>> inventory(InventoryQuery query) {
        var actor=actor("inventory:read"); page(query.page(),query.pageSize()); List<Object> args=new ArrayList<>(List.of(actor.tenantId()));
        String where=" WHERE i.tenant_id=?";
        if(query.departmentId()!=null){if(query.departmentId()<=0)throw new ApiException(400,"门店参数不正确"); actor.requireDepartment("inventory:read",query.departmentId()); where+=" AND i.department_id=?";args.add(query.departmentId());}
        else where += departmentScope(actor, "inventory:read", "i.department_id", args);
        if(query.itemId()!=null){where+=" AND i.item_id=?";args.add(query.itemId());}
        if(query.shortageOnly()) where += " AND i.quantity <= i.warning_value";
        String search=keyword(query.keyword()); where+=" AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!' OR LOWER(COALESCE(x.category,'')) LIKE ? ESCAPE '!')"; args.add(search);args.add(search);args.add(search);
        long total=repo.count("SELECT COUNT(*) FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id"+where,args.toArray()); args.add(query.pageSize());args.add((query.page()-1)*query.pageSize());
        String sql="SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.category,x.unit,x.spec,i.quantity,i.cost_price,i.warning_value,CASE WHEN i.quantity <= i.warning_value THEN 1 ELSE 0 END shortage,i.version,i.update_time FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id"+where+" ORDER BY i.update_time DESC,i.id DESC LIMIT ? OFFSET ?";
        return new Page<>(repo.rows(sql,args.toArray()),total,query.page(),query.pageSize());
    }
    @Transactional public Map<String,Object> changeInventory(InventoryChange input) {
        var actor=write("inventory:write"); actor.requireDepartment("inventory:write",input.departmentId());
        var dept=repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND status=1",actor.tenantId(),input.departmentId()); if(dept==null)throw new ApiException(404,"门店不存在或已停用");
        var item=repo.one("SELECT id,kind,status FROM biz_item WHERE tenant_id=? AND id=?",actor.tenantId(),input.itemId()); if(item==null||!text(item,"kind").equals("PRODUCT")||id(item,"status")!=1)throw new ApiException(400,"库存只能关联启用中的产品");
        var row=repo.one("SELECT * FROM biz_inventory WHERE tenant_id=? AND department_id=? AND item_id=? FOR UPDATE",actor.tenantId(),input.departmentId(),input.itemId());
        BigDecimal current=row==null?BigDecimal.ZERO:new BigDecimal(row.get("quantity").toString()); BigDecimal next;
        BigDecimal changed=input.quantity(); String type=input.changeType();
        if(type.equals("IN")) next=current.add(changed); else if(type.equals("OUT")){next=current.subtract(changed);if(next.signum()<0)throw new ApiException(409,"库存不足，不能出库");} else {next=changed;changed=next.subtract(current).abs();if(next.compareTo(current)==0)throw new ApiException(400,"盘点数量没有变化");}
        BigDecimal warning = input.warningValue();
        long inventoryId;
        if(row==null) inventoryId=repo.insert("INSERT INTO biz_inventory(tenant_id,department_id,item_id,quantity,cost_price,warning_value,version) VALUES(?,?,?,?,?,?,0)",actor.tenantId(),input.departmentId(),input.itemId(),next,input.unitCost(),warning == null ? BigDecimal.ZERO : warning);
        else {inventoryId=id(row,"id"); BigDecimal existingWarning = row.get("warningValue") == null ? BigDecimal.ZERO : new BigDecimal(row.get("warningValue").toString()); repo.update("UPDATE biz_inventory SET quantity=?,cost_price=?,warning_value=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",next,input.unitCost(),warning == null ? existingWarning : warning,actor.tenantId(),inventoryId);}
        repo.insert("INSERT INTO biz_inventory_change(tenant_id,inventory_id,department_id,item_id,change_type,quantity,unit_cost,reason,reference_no,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),inventoryId,input.departmentId(),input.itemId(),type,changed,input.unitCost(),blank(input.reason()),blank(input.referenceNo()),actor.userId());
        return repo.one("SELECT i.id,i.department_id,d.name department_name,i.item_id,x.code item_code,x.name item_name,x.category,x.unit,x.spec,i.quantity,i.cost_price,i.warning_value,CASE WHEN i.quantity <= i.warning_value THEN 1 ELSE 0 END shortage,i.version,i.update_time FROM biz_inventory i JOIN biz_item x ON x.tenant_id=i.tenant_id AND x.id=i.item_id JOIN sys_department d ON d.tenant_id=i.tenant_id AND d.id=i.department_id WHERE i.tenant_id=? AND i.id=?",actor.tenantId(),inventoryId);
    }
    public Page<Map<String,Object>> inventoryChanges(int page,int size,Long departmentId,Long itemId,String changeType,String search,LocalDate startDate,LocalDate endDate) {
        var actor=actor("inventory:read"); page(page,size); List<Object> args=new ArrayList<>(List.of(actor.tenantId())); String where=" WHERE c.tenant_id=?";
        if(departmentId!=null){actor.requireDepartment("inventory:read",departmentId);where+=" AND c.department_id=?";args.add(departmentId);} else where += departmentScope(actor, "inventory:read", "c.department_id", args); if(itemId!=null){where+=" AND c.item_id=?";args.add(itemId);}
        if(changeType!=null&&!changeType.isBlank()){var types=Arrays.stream(changeType.split(",")).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();if(types.stream().anyMatch(value -> !Set.of("IN","OUT","ADJUST").contains(value)))throw new ApiException(400,"流水类型不正确");where+=" AND c.change_type IN ("+marks(types.size())+")";args.addAll(types);}
        if(startDate!=null){where+=" AND c.create_time>=?";args.add(startDate.atStartOfDay());} if(endDate!=null){where+=" AND c.create_time<?";args.add(endDate.plusDays(1).atStartOfDay());}
        var keyword=keyword(search);where+=" AND (LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!' OR LOWER(COALESCE(c.reference_no,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(c.reason,'')) LIKE ? ESCAPE '!')";args.add(keyword);args.add(keyword);args.add(keyword);args.add(keyword);
        long total=repo.count("SELECT COUNT(*) FROM biz_inventory_change c JOIN biz_item x ON x.tenant_id=c.tenant_id AND x.id=c.item_id"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return new Page<>(repo.rows("SELECT c.*,d.name department_name,x.code item_code,x.name item_name FROM biz_inventory_change c JOIN sys_department d ON d.tenant_id=c.tenant_id AND d.id=c.department_id JOIN biz_item x ON x.tenant_id=c.tenant_id AND x.id=c.item_id"+where+" ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }

    public Page<Map<String,Object>> commissions(String type,int page,int size,String search,Integer status) {
        var actor=actor("commissions:read"); page(page,size); String k=commissionKind(type); List<Object> args=new ArrayList<>(List.of(actor.tenantId(),k,keyword(search),keyword(search))); String where=" WHERE tenant_id=? AND kind=? AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(description,'')) LIKE ? ESCAPE '!')";
        if(status!=null){if(status!=0&&status!=1)throw new ApiException(400,"提成状态不正确");where+=" AND status=?";args.add(status);} long total=repo.count("SELECT COUNT(*) FROM biz_commission_scheme"+where,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=repo.rows("SELECT * FROM biz_commission_scheme"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()); enrichRules(rows,actor.tenantId()); return new Page<>(rows,total,page,size);
    }
    @Transactional public long saveCommission(Long id,CommissionSave input) {
        var actor=write("commissions:write"); String k=commissionKind(input.kind()); validateRules(actor.tenantId(),k,input.rules()); long scheme;
        if(id==null) scheme=repo.insert("INSERT INTO biz_commission_scheme(tenant_id,kind,name,basis,rate,fixed_amount,description,status) VALUES(?,?,?,?,?,?,?,?)",actor.tenantId(),k,input.name().trim(),input.basis(),input.rate(),input.fixedAmount(),blank(input.description()),input.status());
        else {if(repo.update("UPDATE biz_commission_scheme SET name=?,basis=?,rate=?,fixed_amount=?,description=?,status=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND kind=?",input.name().trim(),input.basis(),input.rate(),input.fixedAmount(),blank(input.description()),input.status(),actor.tenantId(),id,k)==0)throw new ApiException(404,"提成方案不存在");scheme=id;repo.update("DELETE FROM biz_commission_rule WHERE tenant_id=? AND scheme_id=?",actor.tenantId(),scheme);}
        for(var rule:input.rules()) repo.insert("INSERT INTO biz_commission_rule(tenant_id,scheme_id,item_id,min_amount,max_amount,basis,rate,fixed_amount,sort_order) VALUES(?,?,?,?,?,?,?,?,?)",actor.tenantId(),scheme,rule.itemId(),rule.minAmount(),rule.maxAmount(),rule.basis(),rule.rate(),rule.fixedAmount(),rule.sortOrder()); return scheme;
    }
    @Transactional public void deleteCommission(long id,String type){var actor=write("commissions:write");String k=commissionKind(type);if(repo.update("UPDATE biz_commission_scheme SET status=0,version=version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND kind=?",actor.tenantId(),id,k)==0)throw new ApiException(404,"提成方案不存在");}
    private void validateRules(long tenant,String schemeKind,List<CommissionRule> rules){String expected=schemeKind.equals("STEP")?"PROJECT":schemeKind;for(var rule:rules){if(rule.maxAmount()!=null&&rule.maxAmount().compareTo(rule.minAmount())<0)throw new ApiException(400,"提成阶梯上限不能小于下限");if(rule.itemId()!=null){var item=repo.one("SELECT kind,status FROM biz_item WHERE tenant_id=? AND id=?",tenant,rule.itemId());if(item==null||!text(item,"kind").equals(expected)||id(item,"status")!=1)throw new ApiException(400,"提成关联品项类型不匹配或已停用");}}}
    private void enrichRules(List<Map<String,Object>> rows,long tenant){if(rows.isEmpty())return;String m=marks(rows.size());Object[] ids=rows.stream().map(r->id(r,"id")).toArray();var rules=repo.rows("SELECT r.*,x.code item_code,x.name item_name FROM biz_commission_rule r LEFT JOIN biz_item x ON x.tenant_id=r.tenant_id AND x.id=r.item_id WHERE r.tenant_id=? AND r.scheme_id IN ("+m+") ORDER BY r.sort_order,r.id",concat(tenant,ids));for(var row:rows){long scheme=id(row,"id");row.put("rules",rules.stream().filter(r->id(r,"schemeId")==scheme).toList());}}
    private static Object[] concat(Object first,Object[] rest){Object[] out=new Object[rest.length+1];out[0]=first;System.arraycopy(rest,0,out,1,rest.length);return out;}
}
