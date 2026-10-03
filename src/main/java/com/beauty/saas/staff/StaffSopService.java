package com.beauty.saas.staff;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.StaffSopRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import com.beauty.saas.security.AccountPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class StaffSopService {
    private final IamRepository repo;
    private final AccessService access;
    private final ObjectMapper json;

    private AccountPrincipal read() {
        var actor=access.currentTenant();
        actor.require("users:read");
        return actor;
    }

    private AccountPrincipal writeRule() {
        var actor=access.currentTenant();
        actor.requireGlobal("users:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",actor.tenantId())==null)
            throw new ApiException(409,"企业已停用");
        access.reload(actor).requireGlobal("users:write");
        return actor;
    }

    private void store(AccountPrincipal actor,long storeId,String permission) {
        actor.requireDepartment(permission,storeId);
        if (repo.one("SELECT id FROM sys_department WHERE tenant_id=? AND id=? AND type='STORE' AND status=1",actor.tenantId(),storeId)==null)
            throw new ApiException(404,"门店不存在或已停用");
    }

    private RuleSave parse(String value) {
        try { return json.readValue(value,RuleSave.class); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("SOP 规则数据损坏",cause); }
    }

    private String encode(RuleSave value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("SOP 规则无法保存",cause); }
    }

    private List<Long> positions(long tenant,long ruleId) {
        return repo.rows("SELECT position_id FROM biz_staff_sop_rule_position WHERE tenant_id=? AND rule_id=? ORDER BY position_id",tenant,ruleId)
            .stream().map(row -> id(row,"positionId")).toList();
    }

    public List<Map<String,Object>> availablePositions() {
        var actor=read();
        return repo.rows("SELECT id,code,name,status,create_time FROM biz_staff_position WHERE tenant_id=? AND status=1 ORDER BY id",actor.tenantId());
    }

    public List<Map<String,Object>> rules() {
        var actor=read();
        var names=new HashMap<Long,String>();
        repo.rows("SELECT id,name FROM biz_staff_position WHERE tenant_id=?",actor.tenantId())
            .forEach(row -> names.put(id(row,"id"),text(row,"name")));
        var result=new ArrayList<Map<String,Object>>();
        for (var row : repo.rows("SELECT r.id,r.name,r.all_positions,r.rule_json,r.update_time,u.nickname updater_name " +
            "FROM biz_staff_sop_rule r LEFT JOIN sys_user u ON u.tenant_id=r.tenant_id AND u.id=r.updater_user_id " +
            "WHERE r.tenant_id=? AND r.deleted=0 ORDER BY r.id DESC",actor.tenantId())) {
            long ruleId=id(row,"id");
            var ids=positions(actor.tenantId(),ruleId);
            var rule=new LinkedHashMap<String,Object>();
            rule.put("id",ruleId);
            rule.put("name",row.get("name"));
            rule.put("allPositions",((Number)row.get("allPositions")).intValue()==1);
            rule.put("positionIds",ids);
            rule.put("positionNames",ids.stream().map(names::get).filter(Objects::nonNull).toList());
            rule.put("items",parse(text(row,"ruleJson")).items());
            rule.put("updateTime",row.get("updateTime"));
            rule.put("updaterName",row.get("updaterName"));
            result.add(rule);
        }
        return result;
    }

    private void validate(AccountPrincipal actor,RuleSave input) {
        if (input.name().trim().isBlank()) throw new ApiException(400,"请输入规则名称");
        if (input.allPositions() && !input.positionIds().isEmpty()) throw new ApiException(400,"全店适用时不能指定职位");
        if (!input.allPositions() && input.positionIds().isEmpty()) throw new ApiException(400,"请选择适用职位");
        if (new HashSet<>(input.positionIds()).size()!=input.positionIds().size()) throw new ApiException(400,"适用职位不能重复");
        for (long positionId : input.positionIds()) {
            if (repo.one("SELECT id FROM biz_staff_position WHERE tenant_id=? AND id=? AND status=1",actor.tenantId(),positionId)==null)
                throw new ApiException(400,"适用职位不存在或已停用");
        }
        var keys=new HashSet<String>();
        for (var item : input.items()) {
            if (item.name().trim().isBlank() || !keys.add(item.key()) || !validKey(item.key())) throw new ApiException(400,"自检项名称或标识不正确");
            for (var subitem : item.subitems()) {
                if (subitem.name().trim().isBlank() || !keys.add(subitem.key()) || !validKey(subitem.key())) throw new ApiException(400,"自检子项名称或标识不正确");
            }
        }
    }

    private boolean validKey(String key) { return key.matches("[0-9a-fA-F-]{36}"); }

    @Transactional public long save(Long recordId,RuleSave input) {
        var actor=writeRule();
        long tenant=actor.tenantId();
        if (recordId!=null && repo.one("SELECT id FROM biz_staff_sop_rule WHERE tenant_id=? AND id=? AND deleted=0",tenant,recordId)==null)
            throw new ApiException(404,"自检规则不存在");
        validate(actor,input);
        String name=input.name().trim();
        if (repo.count("SELECT COUNT(*) FROM biz_staff_sop_rule WHERE tenant_id=? AND name=? AND deleted=0 AND id<>?",tenant,name,recordId==null ? 0 : recordId)>0)
            throw new ApiException(409,"规则名称已存在");
        long ruleId;
        if (recordId==null) ruleId=repo.insert("INSERT INTO biz_staff_sop_rule(tenant_id,name,all_positions,rule_json,updater_user_id) VALUES(?,?,?,?,?)",
            tenant,name,input.allPositions() ? 1 : 0,encode(input),actor.userId());
        else {
            ruleId=recordId;
            repo.update("UPDATE biz_staff_sop_rule SET name=?,all_positions=?,rule_json=?,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                name,input.allPositions() ? 1 : 0,encode(input),actor.userId(),tenant,ruleId);
            repo.update("DELETE FROM biz_staff_sop_rule_position WHERE tenant_id=? AND rule_id=?",tenant,ruleId);
        }
        for (long positionId : input.positionIds())
            repo.update("INSERT INTO biz_staff_sop_rule_position(tenant_id,rule_id,position_id) VALUES(?,?,?)",tenant,ruleId,positionId);
        return ruleId;
    }

    @Transactional public void delete(long ruleId) {
        var actor=writeRule();
        if (repo.update("UPDATE biz_staff_sop_rule SET deleted=1,updater_user_id=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND deleted=0",
            actor.userId(),actor.tenantId(),ruleId)==0) throw new ApiException(404,"自检规则不存在");
    }

    private boolean due(String frequency,LocalDate date) {
        return switch (frequency) {
            case "DAILY" -> true;
            case "WEEKLY" -> date.getDayOfWeek()==DayOfWeek.MONDAY;
            case "SEMIMONTHLY" -> date.getDayOfMonth()==1 || date.getDayOfMonth()==16;
            case "MONTHLY" -> date.getDayOfMonth()==1;
            default -> false;
        };
    }

    private boolean applies(RuleSave rule,long positionId) {
        return rule.allPositions() || rule.positionIds().contains(positionId);
    }

    private List<Map<String,Object>> staff(long tenant,long storeId,Long positionId) {
        var rows=repo.rows("SELECT u.id,u.nickname,u.position_id,p.name position_name FROM sys_user u " +
            "LEFT JOIN biz_staff_position p ON p.tenant_id=u.tenant_id AND p.id=u.position_id " +
            "WHERE u.tenant_id=? AND u.deleted=0 AND u.status=1 AND " +
            "(EXISTS(SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.department_id=?) " +
            "OR EXISTS(SELECT 1 FROM sys_tenant_admin ta WHERE ta.tenant_id=u.tenant_id AND ta.user_id=u.id)) " +
            "ORDER BY u.nickname,u.id",tenant,storeId);
        return positionId==null ? rows : rows.stream().filter(row -> row.get("positionId")!=null && id(row,"positionId")==positionId).toList();
    }

    public Map<String,Object> monthly(long storeId,String month,Long positionId) {
        var actor=read();
        store(actor,storeId,"users:read");
        YearMonth period;
        try { period=YearMonth.parse(month); }
        catch (DateTimeParseException cause) { throw new ApiException(400,"月份格式应为 YYYY-MM"); }
        if (positionId!=null && repo.one("SELECT id FROM biz_staff_position WHERE tenant_id=? AND id=?",actor.tenantId(),positionId)==null)
            throw new ApiException(400,"职位不存在");
        var people=staff(actor.tenantId(),storeId,positionId);
        var active=repo.rows("SELECT id,rule_json FROM biz_staff_sop_rule WHERE tenant_id=? AND deleted=0 ORDER BY id",actor.tenantId());
        var result=new ArrayList<Map<String,Object>>();
        for (var person : people) {
            long personPosition=person.get("positionId")==null ? 0 : id(person,"positionId");
            var leaves=new ArrayList<Map<String,Object>>();
            for (var record : active) {
                long ruleId=id(record,"id");
                var rule=parse(text(record,"ruleJson"));
                if (!applies(rule,personPosition)) continue;
                for (var item : rule.items()) {
                    if (item.subitems().isEmpty()) leaves.add(Map.of("ruleId",ruleId,"leafKey",item.key(),"itemName",item.name(),"subitemName","","frequency",item.frequency()));
                    else for (var subitem : item.subitems())
                        leaves.add(Map.of("ruleId",ruleId,"leafKey",subitem.key(),"itemName",item.name(),"subitemName",subitem.name(),"frequency",item.frequency()));
                }
            }
            var entry=new LinkedHashMap<String,Object>(person);
            entry.put("rows",leaves);
            result.add(entry);
        }
        var checks=repo.rows("SELECT user_id,work_date,rule_id,leaf_key,result_mode,result_value FROM biz_staff_sop_check WHERE tenant_id=? AND department_id=? AND work_date BETWEEN ? AND ?",
            actor.tenantId(),storeId,period.atDay(1),period.atEndOfMonth());
        return Map.of("staff",result,"checks",checks);
    }

    @Transactional public void check(CheckSave input) {
        var actor=access.currentTenant();
        long tenant=actor.tenantId(), storeId=input.storeId(), userId=input.userId();
        store(actor,storeId,"users:read");
        if (userId!=actor.userId()) store(actor,storeId,"users:write");
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",tenant)==null)
            throw new ApiException(409,"企业已停用");
        if (input.date().isAfter(LocalDate.now())) throw new ApiException(400,"不能提前完成自检");
        var person=staff(tenant,storeId,null).stream().filter(row -> id(row,"id")==userId).findFirst().orElse(null);
        if (person==null) throw new ApiException(404,"员工不在该门店或已停用");
        var row=repo.one("SELECT rule_json FROM biz_staff_sop_rule WHERE tenant_id=? AND id=? AND deleted=0",tenant,input.ruleId());
        if (row==null) throw new ApiException(404,"自检规则不存在");
        var rule=parse(text(row,"ruleJson"));
        long personPosition=person.get("positionId")==null ? 0 : id(person,"positionId");
        if (!applies(rule,personPosition)) throw new ApiException(403,"规则不适用于该员工");
        var matching=rule.items().stream().filter(item -> item.subitems().isEmpty() && item.key().equals(input.leafKey())
            || item.subitems().stream().anyMatch(subitem -> subitem.key().equals(input.leafKey()))).findFirst().orElse(null);
        if (matching==null || !due(matching.frequency(),input.date())) throw new ApiException(400,"该日期没有此自检项");
        String mode=input.mode()==null ? "COMPLETE" : input.mode();
        String value=input.value()==null ? "" : input.value().trim();
        if (input.checked()) {
            switch (mode) {
                case "COMPLETE" -> {
                    if (!value.isEmpty()) throw new ApiException(400,"仅标记完成不能填写结果内容");
                    value=null;
                }
                case "NUMBER" -> {
                    if (value.isEmpty()) throw new ApiException(400,"请输入数字");
                    try {
                        var number=new BigDecimal(value);
                        if (number.precision()>18 || number.scale()>6) throw new ApiException(400,"数字最多 18 位有效数字和 6 位小数");
                        value=number.stripTrailingZeros().toPlainString();
                        if (value.length()>40) throw new ApiException(400,"数字超出范围");
                    } catch (NumberFormatException cause) { throw new ApiException(400,"请输入有效数字"); }
                }
                case "TEXT" -> { if (value.isEmpty()) throw new ApiException(400,"请输入文字说明"); }
                default -> throw new ApiException(400,"自检结果类型不正确");
            }
        }
        long existing=repo.count("SELECT COUNT(*) FROM biz_staff_sop_check WHERE tenant_id=? AND department_id=? AND user_id=? AND work_date=? AND rule_id=? AND leaf_key=?",
            tenant,storeId,userId,input.date(),input.ruleId(),input.leafKey());
        if (input.checked() && existing==0) repo.insert("INSERT INTO biz_staff_sop_check(tenant_id,department_id,user_id,work_date,rule_id,leaf_key,result_mode,result_value) VALUES(?,?,?,?,?,?,?,?)",
            tenant,storeId,userId,input.date(),input.ruleId(),input.leafKey(),mode,value);
        if (input.checked() && existing>0) repo.update("UPDATE biz_staff_sop_check SET result_mode=?,result_value=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND department_id=? AND user_id=? AND work_date=? AND rule_id=? AND leaf_key=?",
            mode,value,tenant,storeId,userId,input.date(),input.ruleId(),input.leafKey());
        if (!input.checked() && existing>0) repo.update("DELETE FROM biz_staff_sop_check WHERE tenant_id=? AND department_id=? AND user_id=? AND work_date=? AND rule_id=? AND leaf_key=?",
            tenant,storeId,userId,input.date(),input.ruleId(),input.leafKey());
    }
}
