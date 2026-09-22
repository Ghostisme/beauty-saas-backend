package com.beauty.saas.sms;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.SmsRequests.*;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import com.beauty.saas.security.AccountPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class SmsService {
    private final IamRepository repo;
    private final AccessService access;
    private final HttpServletRequest request;
    private final ObjectMapper json;
    private static final String RECORD_FROM=" FROM biz_sms_record r JOIN sys_tenant t ON t.id=r.tenant_id";
    private static final String RECHARGE_FROM=" FROM biz_sms_recharge r JOIN sys_tenant t ON t.id=r.tenant_id";
    private static final String RECHARGE_COLUMNS="r.id,r.tenant_id,r.order_no,r.package_id,r.package_version,r.package_name,r.units,r.amount,r.status,r.version,r.create_time,r.paid_time,r.cancelled_time,t.name tenant_name,t.code tenant_code,t.status tenant_status";

    private AccountPrincipal reader(String permission,boolean allowAggregate) {
        var actor=access.current();
        if (!actor.platformAdmin() || !allowAggregate || request.getHeader("X-Tenant-Id")!=null) actor=access.currentTenant();
        actor.requireGlobal(permission);
        return actor;
    }
    private AccountPrincipal writer(String permission) {
        var actor=reader(permission,false);
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",actor.tenantId())==null) throw new ApiException(409,"企业已停用，请先由平台启用");
        actor=access.reload(actor); actor.requireGlobal(permission);
        return actor;
    }
    private String scope(AccountPrincipal actor,List<Object> args,String column) {
        if (actor.platformAdmin() && actor.tenantId()==0) return "";
        args.add(actor.tenantId()); return " AND "+column+"=?";
    }
    public Map<String,Object> status() {
        var actor=access.current();
        if (!actor.platformAdmin()) access.currentTenant();
        if (!List.of("sms-settings:read","sms-records:read","sms-billing:read").stream().anyMatch(actor::global)) throw new ApiException(403,"没有短信模块权限");
        // No provider credentials or payment adapter have been selected. Never claim delivery/payment readiness.
        return Map.of("sendingAvailable",false,"paymentAvailable",false,"message","短信发送与充值收款通道尚未接入。可保存通知设置和待支付充值单，暂不发送短信、扣款或增加余额。");
    }
    public Map<String,Object> settings() {
        var actor=reader("sms-settings:read",false);
        var saved=repo.rows("SELECT * FROM biz_sms_setting WHERE tenant_id=?",actor.tenantId());
        var groups=SmsCatalog.GROUPS.stream().map(group -> Map.of("key",group.key(),"title",group.title(),"templates",group.templates().stream().map(template -> setting(template,saved.stream().filter(row -> text(row,"templateCode").equals(template.code())).findFirst().orElse(null))).toList())).toList();
        return Map.of("groups",groups);
    }
    private Map<String,Object> setting(SmsCatalog.Template template,Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("code",template.code()); result.put("name",template.name()); result.put("content",template.content()); result.put("configKind",template.configKind());
        result.put("enabled",row!=null && id(row,"enabled")==1); result.put("version",row==null?0L:id(row,"version"));
        if (row!=null && row.get("configJson")!=null) {
            try { result.put("config",normalizeConfig(template,json.readValue(text(row,"configJson"),ReminderConfig.class))); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored SMS reminder configuration",e); }
        }
        return result;
    }
    private void validateConfig(SmsCatalog.Template template,SettingSave input) {
        var config=input.config();
        if (template.configKind().equals("NONE")) { if (config!=null) throw new ApiException(400,"该模板不支持提醒时间设置"); return; }
        if (config==null) { if (input.enabled()) throw new ApiException(400,"请先配置提醒规则再开启此通知"); return; }
        if (template.configKind().equals("APPOINTMENT")) {
            if (config.leadMinutes()==null || config.advanceDays()!=null || config.sendTime()!=null || config.visitNote()!=null || config.benefitNote()!=null || config.customBenefitEnabled()!=null) throw new ApiException(400,"预约提醒只支持提前提醒分钟数");
        } else {
            if (config.leadMinutes()!=null || config.advanceDays()==null) throw new ApiException(400,"请设置生日提醒的提前天数");
            if (customBenefit(config) && (!validNote(config.visitNote()) || !validNote(config.benefitNote()))) throw new ApiException(400,"请完整填写自定义到店时间说明与生日权益");
            if (hasControlCharacter(config.visitNote()) || hasControlCharacter(config.benefitNote())) throw new ApiException(400,"到店福利不能包含控制字符");
        }
    }
    private boolean hasControlCharacter(String value) { return value!=null && value.chars().anyMatch(Character::isISOControl); }
    private boolean validNote(String value) { return value!=null && !value.isBlank() && !hasControlCharacter(value); }
    private boolean customBenefit(ReminderConfig config) {
        // Existing configurations predate the switch: keep their custom benefits enabled.
        return config.customBenefitEnabled()!=null ? config.customBenefitEnabled() : config.visitNote()!=null || config.benefitNote()!=null;
    }
    private ReminderConfig normalizeConfig(SmsCatalog.Template template,ReminderConfig config) {
        if (config==null || !template.configKind().equals("BIRTHDAY")) return config;
        return new ReminderConfig(null,config.advanceDays(),Objects.requireNonNullElse(config.sendTime(),"09:00"),
            Objects.requireNonNullElse(config.visitNote(),""),Objects.requireNonNullElse(config.benefitNote(),"生日权益"),customBenefit(config));
    }
    @Transactional public Map<String,Object> saveSetting(String code,SettingSave input) {
        var actor=writer("sms-settings:write"); actor.requireGlobal("sms-settings:read");
        var template=SmsCatalog.template(code); validateConfig(template,input);
        var previous=repo.one("SELECT * FROM biz_sms_setting WHERE tenant_id=? AND template_code=?",actor.tenantId(),code);
        if ((previous==null?0L:id(previous,"version"))!=input.version()) throw new ApiException(409,"短信设置已被其他人更新，请重新加载后保存");
        String config=null;
        try { if (input.config()!=null) config=json.writeValueAsString(normalizeConfig(template,input.config())); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        if (previous==null) repo.update("INSERT INTO biz_sms_setting(tenant_id,template_code,enabled,config_json,version,updated_by) VALUES(?,?,?,?,1,?)",actor.tenantId(),code,input.enabled()?1:0,config,actor.userId());
        else repo.update("UPDATE biz_sms_setting SET enabled=?,config_json=?,version=version+1,updated_by=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND template_code=?",input.enabled()?1:0,config,actor.userId(),actor.tenantId(),code);
        audit(actor,"SMS_SETTING",template.name()+"："+(input.enabled()?"开启":"关闭")+"；保存通知配置（未发送短信）");
        return setting(template,repo.one("SELECT * FROM biz_sms_setting WHERE tenant_id=? AND template_code=?",actor.tenantId(),code));
    }
    private void page(int page,int size) { if (page<1 || page>1_000_000 || size<1 || size>100) throw new ApiException(400,"分页参数不正确，每页最多 100 条"); }
    private String recordWhere(AccountPrincipal actor,RecordQuery query,List<Object> args) {
        page(query.page(),query.pageSize());
        String phone=Objects.toString(query.phone(),"").trim();
        if (!phone.matches("[0-9+ -]{0,30}")) throw new ApiException(400,"手机号筛选仅支持数字、加号、空格和连字符，最长 30 位");
        LocalDate start=query.startDate(),end=query.endDate();
        if ((start==null)!=(end==null)) throw new ApiException(400,"请完整选择起止日期");
        if (start==null) { end=LocalDate.now(ZoneId.of("Asia/Shanghai")); start=end.withDayOfMonth(1); }
        if (start.isAfter(end) || start.getYear()<1900 || end.getYear()>9998) throw new ApiException(400,"日期范围不正确");
        String where=" WHERE r.send_time>=? AND r.send_time<?"; args.add(start.atStartOfDay()); args.add(end.plusDays(1).atStartOfDay());
        where+=scope(actor,args,"r.tenant_id");
        if (!phone.isEmpty()) { where+=" AND r.phone LIKE ?"; args.add("%"+phone+"%"); }
        return where;
    }
    private List<Map<String,Object>> recordRows(String where,List<Object> args,int limit,int offset) {
        var parameters=new ArrayList<>(args); parameters.add(limit); parameters.add(offset);
        var rows=repo.rows("SELECT r.id,r.tenant_id,r.template_code,r.phone,r.content,r.status,r.billed_units,r.failure_reason,r.send_time,r.delivered_time,t.name tenant_name,t.code tenant_code"+RECORD_FROM+where+" ORDER BY r.send_time DESC,r.id DESC LIMIT ? OFFSET ?",parameters.toArray());
        rows.forEach(row -> { row.put("templateName",SmsCatalog.name(text(row,"templateCode"))); row.put("statusLabel",SmsCsv.status(text(row,"status"))); });
        return rows;
    }
    public Page<Map<String,Object>> records(RecordQuery query) {
        var actor=reader("sms-records:read",true); List<Object> args=new ArrayList<>(); String where=recordWhere(actor,query,args);
        long total=repo.count("SELECT COUNT(*)"+RECORD_FROM+where,args.toArray());
        return new Page<>(recordRows(where,args,query.pageSize(),(query.page()-1)*query.pageSize()),total,query.page(),query.pageSize());
    }
    @Transactional public byte[] exportRecords(RecordQuery query) {
        var actor=reader("sms-records:write",true); actor.requireGlobal("sms-records:read");
        List<Object> args=new ArrayList<>(); String where=recordWhere(actor,query,args);
        var rows=recordRows(where,args,5001,0);
        if (rows.size()>5000) throw new ApiException(400,"一次最多导出 5000 条短信记录，请缩小日期范围");
        byte[] csv=SmsCsv.render(rows); audit(actor,"SMS_EXPORT","导出短信发送记录 "+rows.size()+" 条"); return csv;
    }
    public Map<String,Object> billing() {
        var actor=reader("sms-billing:read",true); List<Object> args=new ArrayList<>();
        String scope=scope(actor,args,"a.tenant_id");
        long balance=repo.count("SELECT COALESCE(SUM(a.balance),0) FROM biz_sms_account a JOIN sys_tenant t ON t.id=a.tenant_id WHERE 1=1"+scope,args.toArray());
        var packages=repo.rows("SELECT id,code,name,units,price,version FROM biz_sms_package WHERE active=1 ORDER BY sort_order,id");
        packages.forEach(pack -> pack.put("price",new BigDecimal(pack.get("price").toString()).setScale(2).toPlainString()));
        return Map.of("balance",String.valueOf(balance),"aggregate",actor.platformAdmin() && actor.tenantId()==0,"packages",packages,"paymentAvailable",false);
    }
    public Page<Map<String,Object>> recharges(int page,int size) {
        page(page,size); var actor=reader("sms-billing:read",true); List<Object> args=new ArrayList<>(); String where=" WHERE 1=1"+scope(actor,args,"r.tenant_id");
        long total=repo.count("SELECT COUNT(*)"+RECHARGE_FROM+where,args.toArray()); args.add(size); args.add((page-1)*size);
        var rows=repo.rows("SELECT "+RECHARGE_COLUMNS+RECHARGE_FROM+where+" ORDER BY r.create_time DESC,r.id DESC LIMIT ? OFFSET ?",args.toArray());
        rows.forEach(row -> rechargeView(row,actor)); return new Page<>(rows,total,page,size);
    }
    private Map<String,Object> rechargeView(Map<String,Object> row,AccountPrincipal actor) {
        row.put("amount",new BigDecimal(row.get("amount").toString()).setScale(2).toPlainString());
        row.put("canCancel",actor.global("sms-billing:write") && id(row,"tenantStatus")==1 && text(row,"status").equals("PENDING"));
        return row;
    }
    private Map<String,Object> recharge(AccountPrincipal actor,long id) {
        List<Object> args=new ArrayList<>(List.of(id)); String where=" WHERE r.id=?"+scope(actor,args,"r.tenant_id");
        var row=repo.one("SELECT "+RECHARGE_COLUMNS+RECHARGE_FROM+where,args.toArray());
        if (row==null) throw new ApiException(404,"充值订单不存在或不在当前企业范围内");
        return rechargeView(row,actor);
    }
    public Map<String,Object> recharge(long id) { return recharge(reader("sms-billing:read",true),id); }
    @Transactional public Map<String,Object> createRecharge(RechargeCreate input) {
        var actor=writer("sms-billing:write"); actor.requireGlobal("sms-billing:read");
        String key=input.idempotencyKey().toLowerCase(Locale.ROOT);
        var previous=repo.one("SELECT id,package_id,package_version FROM biz_sms_recharge WHERE tenant_id=? AND idempotency_key=?",actor.tenantId(),key);
        if (previous!=null) {
            if (id(previous,"packageId")!=input.packageId() || id(previous,"packageVersion")!=input.packageVersion()) throw new ApiException(409,"请勿复用其他套餐的充值请求，请重新打开充值窗口");
            return recharge(actor,id(previous,"id"));
        }
        var pack=repo.one("SELECT * FROM biz_sms_package WHERE id=? AND active=1 FOR UPDATE",input.packageId());
        if (pack==null) throw new ApiException(404,"短信套餐已下架，请刷新后选择其他套餐");
        if (id(pack,"version")!=input.packageVersion()) throw new ApiException(409,"套餐价格或内容已更新，请刷新后重新确认");
        if (repo.count("SELECT COUNT(*) FROM biz_sms_recharge WHERE tenant_id=? AND status='PENDING'",actor.tenantId())>=10) throw new ApiException(409,"待支付充值单最多保留 10 笔，请先在充值记录中取消不需要的订单");
        String number="SMS"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        long id=repo.insert("INSERT INTO biz_sms_recharge(tenant_id,order_no,package_id,package_version,package_name,units,amount,status,idempotency_key,created_by) VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",actor.tenantId(),number,input.packageId(),id(pack,"version"),text(pack,"name"),id(pack,"units"),pack.get("price"),key,actor.userId());
        // Only an authenticated provider settlement may ever credit the account. No such endpoint exists in this release.
        audit(actor,"SMS_RECHARGE_CREATE","创建待支付短信充值单 "+number+"，未扣款、未增加余额"); return recharge(actor,id);
    }
    @Transactional public Map<String,Object> cancelRecharge(long id,RechargeCancel input) {
        var actor=writer("sms-billing:write"); actor.requireGlobal("sms-billing:read");
        var row=repo.one("SELECT id,status,version,order_no FROM biz_sms_recharge WHERE tenant_id=? AND id=? FOR UPDATE",actor.tenantId(),id);
        if (row==null) throw new ApiException(404,"充值订单不存在或不在当前企业范围内");
        if (id(row,"version")!=input.version()) throw new ApiException(409,"充值订单已更新，请刷新后重试");
        if (!text(row,"status").equals("PENDING")) throw new ApiException(409,"只能取消待支付充值单");
        repo.update("UPDATE biz_sms_recharge SET status='CANCELLED',version=version+1,cancelled_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",actor.tenantId(),id);
        audit(actor,"SMS_RECHARGE_CANCEL","取消待支付短信充值单 "+text(row,"orderNo")); return recharge(actor,id);
    }
    private void audit(AccountPrincipal actor,String action,String detail) {
        Long tenant=actor.tenantId()==0?null:actor.tenantId();
        repo.update("INSERT INTO biz_sms_audit(tenant_id,actor_id,action,detail) VALUES(?,?,?,?)",tenant,actor.userId(),action,detail);
        if (actor.platformAdmin()) repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,?,?)",actor.userId(),actor.tenantId(),action,detail);
    }
}
