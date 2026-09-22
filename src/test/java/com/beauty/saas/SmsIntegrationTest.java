package com.beauty.saas;

import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.sms.SmsCsv;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:sms;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "jwt.secret=sms-integration-test-isolated-signing-key-123456789","platform.provisioning-key=",
    "platform.bootstrap-password=SmsPlatform123!"})
@AutoConfigureMockMvc
@Transactional
class SmsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;
    static final String PASS="SmsCompany123!", RANGE="startDate=2026-09-01&endDate=2026-09-22";
    record Enterprise(long id,String code,String token) {}
    String root; Enterprise a,b;
    MvcResult call(String method,String path,String token,Long tenant,Object body) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api"+path).contextPath("/api");
        if (token!=null) request.header("Authorization","Bearer "+token);
        if (tenant!=null) request.header("X-Tenant-Id",tenant);
        if (body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request).andReturn();
    }
    JsonNode data(MvcResult result,int status) throws Exception {
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    JsonNode get(String path) throws Exception { return data(call("GET",path,a.token(),null,null),200); }
    Enterprise enterprise(String code) throws Exception {
        long id=data(call("POST","/platform/tenants",root,null,Map.of("code",code,"name","企业"+code,"adminName","负责人","adminPassword",PASS)),200).path("tenantId").asLong();
        String token=data(call("POST","/user/login",null,null,Map.of("tenantCode",code,"username","admin","password",PASS)),200).path("token").asText();
        return new Enterprise(id,code,token);
    }
    @BeforeEach void setup() throws Exception {
        root=data(call("POST","/user/login",null,null,Map.of("username","admin","password","SmsPlatform123!")),200).path("token").asText();
        a=enterprise("sms-a"); b=enterprise("sms-b");
    }
    JsonNode template(JsonNode settings,String code) { for(var group:settings.path("groups")) for(var item:group.path("templates")) if(item.path("code").asText().equals(code)) return item; throw new AssertionError(code); }
    JsonNode save(String code,boolean enabled,long version,Map<String,Object> config,int expected) throws Exception {
        var body=new HashMap<String,Object>(Map.of("enabled",enabled,"version",version)); if(config!=null) body.put("config",config);
        return data(call("PUT","/sms/settings/"+code,a.token(),null,body),expected);
    }
    Map<String,Object> purchase(String key,long packageId,long version) { return Map.of("packageId",packageId,"packageVersion",version,"idempotencyKey",key); }
    long record(Enterprise e,String phone,String time,String content) {
        return repo.insert("INSERT INTO biz_sms_record(tenant_id,template_code,phone,content,status,billed_units,business_key,send_time) VALUES(?,'CARD_RECHARGE',?,?,'DELIVERED',1,?,?)",e.id(),phone,content,UUID.randomUUID().toString(),time);
    }
    String staff(List<String> permissions) throws Exception {
        String name="staff"+UUID.randomUUID().toString().substring(0,8);
        long role=data(call("POST","/iam/roles",a.token(),null,Map.of("code",name.toUpperCase(Locale.ROOT),"name",name,"status",1,"permissionCodes",permissions)),200).asLong();
        data(call("POST","/iam/users",a.token(),null,Map.of("username",name,"nickname",name,"password",PASS,"status",1,"departmentIds",List.of(),"roleGrants",List.of(Map.of("roleId",role)))),200);
        return data(call("POST","/user/login",null,null,Map.of("tenantCode",a.code(),"username",name,"password",PASS)),200).path("token").asText();
    }
    @Test void defaultsHaveThirteenTemplatesAndGetNeverCreatesQuotaOrSettings() throws Exception {
        var settings=get("/sms/settings"); assertThat(settings.path("groups").size()).isEqualTo(4);
        int count=0; for (var group:settings.path("groups")) for (var item:group.path("templates")) { count++; assertThat(item.path("enabled").asBoolean()).isFalse(); assertThat(item.path("version").asInt()).isZero(); }
        assertThat(count).isEqualTo(13); assertThat(template(settings,"CARD_RECHARGE").path("content").asText()).contains("${shop_name}");
        assertThat(get("/sms/status").path("sendingAvailable").asBoolean()).isFalse(); assertThat(get("/sms/status").path("paymentAvailable").asBoolean()).isFalse();
        var billing=get("/sms/billing"); assertThat(billing.path("balance").asText()).isEqualTo("0"); assertThat(billing.path("packages").size()).isEqualTo(5);
        assertThat(billing.path("packages").get(0).path("price").asText()).isEqualTo("68.00"); assertThat(billing.path("packages").get(4).path("units").asInt()).isEqualTo(50000);
        assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_setting")).isZero(); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_account")).isZero(); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_record")).isZero();
    }
    @Test void switchesPersistWithOptimisticConcurrencyAndWithoutSendingAnything() throws Exception {
        assertThat(save("CARD_RECHARGE",true,0,null,200).path("version").asInt()).isEqualTo(1);
        assertThat(template(get("/sms/settings"),"CARD_RECHARGE").path("enabled").asBoolean()).isTrue();
        save("CARD_RECHARGE",false,0,null,409); assertThat(template(get("/sms/settings"),"CARD_RECHARGE").path("enabled").asBoolean()).isTrue();
        save("CARD_RECHARGE",false,1,null,200); save("UNKNOWN",true,0,null,404);
        assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_record")).isZero(); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_audit WHERE tenant_id=?",a.id())).isEqualTo(2);
    }
    @Test void reminderConfigurationValidatesBeforeEnableAndRoundTrips() throws Exception {
        save("APPOINTMENT_REMINDER",true,0,null,400); save("APPOINTMENT_REMINDER",true,0,Map.of("leadMinutes",0),400);
        save("APPOINTMENT_REMINDER",true,0,Map.of("leadMinutes",4321),400); save("CARD_OPEN",true,0,Map.of("leadMinutes",30),400);
        save("APPOINTMENT_REMINDER",true,0,Map.of("leadMinutes",30,"advanceDays",1),400);
        save("APPOINTMENT_REMINDER",true,0,Map.of("leadMinutes",1),200);
        assertThat(template(get("/sms/settings"),"APPOINTMENT_REMINDER").path("config").path("leadMinutes").asInt()).isEqualTo(1);
        var birthday=new HashMap<String,Object>(Map.of("advanceDays",1,"sendTime","09:00","visitNote","生日当月","benefitNote","专属护理"));
        save("BIRTHDAY_GREETING",false,0,birthday,200); birthday.put("sendTime","25:01"); save("BIRTHDAY_GREETING",true,1,birthday,400);
        birthday.put("sendTime","09:00"); birthday.put("advanceDays",31); save("BIRTHDAY_GREETING",true,1,birthday,400);
        birthday.put("advanceDays",0); birthday.put("benefitNote"," "); save("BIRTHDAY_GREETING",true,1,birthday,400);
        birthday.put("benefitNote","护理\n活动"); save("BIRTHDAY_GREETING",true,1,birthday,400);
        birthday.put("benefitNote","护理活动"); save("BIRTHDAY_GREETING",true,1,birthday,200);
    }
    @Test void birthdayDefaultsAndCustomSwitchPersistWithoutSendingOrDiscardingSavedBenefits() throws Exception {
        var saved=save("BIRTHDAY_GREETING",false,0,Map.of("advanceDays",0,"customBenefitEnabled",false),200);
        assertThat(saved.path("enabled").asBoolean()).isFalse();
        assertThat(saved.path("config").path("customBenefitEnabled").asBoolean()).isFalse();
        assertThat(saved.path("config").path("sendTime").asText()).isEqualTo("09:00");
        assertThat(saved.path("config").path("benefitNote").asText()).isEqualTo("生日权益");
        save("BIRTHDAY_GREETING",false,1,Map.of("advanceDays",1,"customBenefitEnabled",true),400);
        var custom=new HashMap<String,Object>(Map.of("advanceDays",1,"customBenefitEnabled",true,"visitNote","生日当月","benefitNote","专属护理"));
        save("BIRTHDAY_GREETING",false,1,custom,200);
        custom.put("customBenefitEnabled",false);
        save("BIRTHDAY_GREETING",false,2,custom,200);
        var after=template(get("/sms/settings"),"BIRTHDAY_GREETING").path("config");
        assertThat(after.path("customBenefitEnabled").asBoolean()).isFalse();
        assertThat(after.path("benefitNote").asText()).isEqualTo("专属护理");
        assertThat(after.path("visitNote").asText()).isEqualTo("生日当月");
        custom.put("customBenefitEnabled",true); save("BIRTHDAY_GREETING",true,3,custom,200);
        assertThat(template(get("/sms/settings"),"BIRTHDAY_GREETING").path("config").path("customBenefitEnabled").asBoolean()).isTrue();
        assertThat(template(data(call("GET","/sms/settings",b.token(),null,null),200),"BIRTHDAY_GREETING").has("config")).isFalse();
        assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_record")).isZero();
    }
    @Test void oldBirthdayConfigIsReadAsCustomWithoutRewritingStoredSettings() throws Exception {
        String legacy="{\"advanceDays\":2,\"sendTime\":\"10:30\",\"visitNote\":\"生日当周\",\"benefitNote\":\"原有生日权益\"}";
        repo.update("INSERT INTO biz_sms_setting(tenant_id,template_code,enabled,config_json,version,updated_by) VALUES(?,'BIRTHDAY_GREETING',1,?,3,1)",a.id(),legacy);
        var read=template(get("/sms/settings"),"BIRTHDAY_GREETING");
        assertThat(read.path("enabled").asBoolean()).isTrue(); assertThat(read.path("version").asInt()).isEqualTo(3);
        assertThat(read.path("config").path("customBenefitEnabled").asBoolean()).isTrue();
        assertThat(read.path("config").path("sendTime").asText()).isEqualTo("10:30");
        assertThat(read.path("config").path("benefitNote").asText()).isEqualTo("原有生日权益");
        assertThat(repo.one("SELECT config_json FROM biz_sms_setting WHERE tenant_id=?",a.id()).get("configJson")).isEqualTo(legacy);
    }
    @Test void recordsUseTenantFilterDateBoundariesStablePagingAndServerSideSearch() throws Exception {
        long earlier=record(a,"13800000001","2026-09-01 00:00:00","中文记录");
        record(a,"13800000002","2026-09-22 23:59:59","最后一秒"); record(a,"13800000003","2026-09-23 00:00:00","翌日排除"); record(b,"13800000001","2026-09-22 12:00:00","企业B");
        var first=get("/sms/records?"+RANGE+"&pageSize=1&page=2"); assertThat(first.path("total").asInt()).isEqualTo(2); assertThat(first.path("records").get(0).path("id").asLong()).isEqualTo(earlier);
        assertThat(get("/sms/records?"+RANGE+"&phone=0000002").path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/sms/records?"+RANGE,root,null,null),200).path("total").asInt()).isEqualTo(3);
        var other=data(call("GET","/sms/records?"+RANGE,root,b.id(),null),200); assertThat(other.path("total").asInt()).isEqualTo(1); assertThat(other.toString()).doesNotContain("中文记录");
        for(String query:List.of("page=0","pageSize=101","startDate=2026-09-01","startDate=2026-09-22&endDate=2026-09-01","phone=abc","phone=138_")) data(call("GET","/sms/records?"+query,a.token(),null,null),400);
    }
    @Test void csvExportHasUtf8BomQuotingFormulaProtectionFilterAndAudit() throws Exception {
        record(a,"+8613800000001","2026-09-22 12:00:00","  =HYPERLINK(\"x\"),\n中文"); record(b,"13800000002","2026-09-22 12:00:00","不可泄露");
        var response=call("GET","/sms/records/export?"+RANGE,a.token(),null,null).getResponse(); assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/csv"); assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String csv=response.getContentAsString(StandardCharsets.UTF_8); assertThat(csv).startsWith("\uFEFF所属企业").contains("\"'+8613800000001\"","\"'  =HYPERLINK(\"\"x\"\"),\n中文\"").doesNotContain("不可泄露");
        var rootCsv=call("GET","/sms/records/export?"+RANGE,root,null,null); assertThat(rootCsv.getResponse().getStatus()).isEqualTo(200);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_platform_audit WHERE action='SMS_EXPORT' AND tenant_id=0")).isEqualTo(1);
        assertThat(SmsCsv.cell("\t@cmd")).isEqualTo("\"'\t@cmd\""); assertThat(SmsCsv.cell("\uFF1D1+1")).startsWith("\"'");
    }
    @Test void exportRejectsOversizedResultsInsteadOfSilentTruncation() throws Exception {
        repo.update("INSERT INTO biz_sms_record(tenant_id,template_code,phone,content,status,business_key,send_time) SELECT ?,'CARD_OPEN','13800000001','test','PENDING',CONCAT('bulk-',n.x),'2026-09-22 12:00:00' FROM SYSTEM_RANGE(1,5001) n(x)",a.id());
        data(call("GET","/sms/records/export?"+RANGE,a.token(),null,null),400); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_audit WHERE action='SMS_EXPORT'")).isZero();
    }
    @Test void pendingRechargeUsesServerPriceAndIdempotencyNeverCreditsBalance() throws Exception {
        String key=UUID.randomUUID().toString(); var body=purchase(key,1,1);
        var first=data(call("POST","/sms/recharges",a.token(),null,body),200); long id=first.path("id").asLong();
        assertThat(first.path("status").asText()).isEqualTo("PENDING"); assertThat(first.path("amount").asText()).isEqualTo("68.00"); assertThat(first.path("units").asInt()).isEqualTo(1000);
        var again=data(call("POST","/sms/recharges",a.token(),null,purchase(key.toUpperCase(Locale.ROOT),1,1)),200); assertThat(again.path("id").asLong()).isEqualTo(id);
        data(call("POST","/sms/recharges",a.token(),null,purchase(key,2,1)),409);
        assertThat(get("/sms/billing").path("balance").asText()).isEqualTo("0"); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_account")).isZero(); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_credit_ledger")).isZero();
        assertThat(get("/sms/recharges").path("total").asInt()).isEqualTo(1); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_audit WHERE action='SMS_RECHARGE_CREATE'")).isEqualTo(1);
        repo.update("UPDATE biz_sms_package SET price=79.50,version=2 WHERE id=1"); assertThat(get("/sms/recharges/"+id).path("amount").asText()).isEqualTo("68.00");
        data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),1,1)),409);
        assertThat(data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),1,2)),200).path("amount").asText()).isEqualTo("79.50");
    }
    @Test void cancellationsRequireOwnerScopeVersionAndPendingStateAndCannotChangeQuota() throws Exception {
        var created=data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),1,1)),200); long id=created.path("id").asLong();
        data(call("GET","/sms/recharges/"+id,b.token(),null,null),404); data(call("POST","/sms/recharges/"+id+"/cancel",b.token(),null,Map.of("version",0)),404);
        data(call("POST","/sms/recharges/"+id+"/cancel",a.token(),null,Map.of("version",9)),409);
        assertThat(data(call("POST","/sms/recharges/"+id+"/cancel",root,a.id(),Map.of("version",0)),200).path("status").asText()).isEqualTo("CANCELLED");
        data(call("POST","/sms/recharges/"+id+"/cancel",a.token(),null,Map.of("version",1)),409);
        assertThat(get("/sms/billing").path("balance").asText()).isEqualTo("0"); assertThat(repo.count("SELECT COUNT(*) FROM biz_sms_credit_ledger")).isZero();
    }
    @Test void tenantHeadersAndPlatformWritesRequireExplicitScopeAndActiveEnterprise() throws Exception {
        for(String path:List.of("/sms/settings","/sms/records","/sms/billing","/sms/recharges")) { data(call("GET",path,a.token(),b.id(),null),403); data(call("GET",path,null,null,null),401); }
        data(call("GET","/sms/settings",root,null,null),400);
        data(call("POST","/sms/recharges",root,null,purchase(UUID.randomUUID().toString(),1,1)),400);
        repo.update("UPDATE sys_tenant SET status=0 WHERE id=?",b.id());
        data(call("GET","/sms/settings",root,b.id(),null),200);
        data(call("PUT","/sms/settings/CARD_OPEN",root,b.id(),Map.of("enabled",true,"version",0)),409);
        data(call("POST","/sms/recharges",root,b.id(),purchase(UUID.randomUUID().toString(),1,1)),409);
    }
    @Test void independentReadPermissionsDoNotAllowSettingsExportOrRechargeWrites() throws Exception {
        String viewer=staff(List.of("sms-settings:read","sms-records:read","sms-billing:read"));
        data(call("GET","/sms/settings",viewer,null,null),200); data(call("GET","/sms/records",viewer,null,null),200); data(call("GET","/sms/billing",viewer,null,null),200);
        data(call("PUT","/sms/settings/CARD_OPEN",viewer,null,Map.of("enabled",true,"version",0)),403); data(call("GET","/sms/records/export?"+RANGE,viewer,null,null),403);
        data(call("POST","/sms/recharges",viewer,null,purchase(UUID.randomUUID().toString(),1,1)),403);
        String unrelated=staff(List.of("home:read")); data(call("GET","/sms/status",unrelated,null,null),403); data(call("GET","/sms/billing",unrelated,null,null),403);
        data(call("POST","/iam/roles",a.token(),null,Map.of("code","BADWRITE","name","错误授权","status",1,"permissionCodes",List.of("sms-settings:write"))),400);
    }
    @Test void purchaseValidatesInputsAndLimitsPendingOrdersWithoutChangingBalance() throws Exception {
        data(call("POST","/sms/recharges",a.token(),null,purchase("bad-key",1,1)),400);
        data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),0,1)),400);
        data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),99,1)),404);
        repo.update("UPDATE biz_sms_package SET active=0 WHERE id=5"); data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),5,1)),404);
        for(int i=0;i<10;i++) data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),1,1)),200);
        data(call("POST","/sms/recharges",a.token(),null,purchase(UUID.randomUUID().toString(),1,1)),409);
        assertThat(get("/sms/recharges?page=2&pageSize=3").path("records").size()).isEqualTo(3); assertThat(get("/sms/billing").path("balance").asText()).isEqualTo("0");
    }
}
