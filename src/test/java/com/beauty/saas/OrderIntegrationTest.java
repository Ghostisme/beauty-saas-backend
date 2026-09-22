package com.beauty.saas;

import com.beauty.saas.iam.IamRepository;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "jwt.secret=order-integration-test-isolated-signing-key-123456789","platform.provisioning-key=",
    "platform.bootstrap-password=OrderPlatform123!"})
@AutoConfigureMockMvc
@Transactional
class OrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;
    @Autowired DataSource source;
    static final String PASS="OrderCompany123!";
    record Enterprise(long id,String code,String token) {}
    String root;
    Enterprise a,b;
    long storeA,storeA2,storeB,first,second,nextDay,pending,foreign;
    MvcResult call(String method,String path,String token,Long tenant,Object body) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api"+path).contextPath("/api");
        if (token!=null) request.header("Authorization","Bearer "+token);
        if (tenant!=null) request.header("X-Tenant-Id",tenant);
        if (body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request).andReturn();
    }
    JsonNode data(MvcResult result,int status) throws Exception {
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    JsonNode get(String path) throws Exception { return data(call("GET",path,a.token(),null,null),200); }
    Enterprise enterprise(String code) throws Exception {
        long id=data(call("POST","/platform/tenants",root,null,Map.of("code",code,"name","企业"+code,"adminName","负责人","adminPassword",PASS)),200).path("tenantId").asLong();
        String token=login(code,"admin");
        return new Enterprise(id,code,token);
    }
    String login(String code,String user) throws Exception { return data(call("POST","/user/login",null,null,Map.of("tenantCode",code,"username",user,"password",PASS)),200).path("token").asText(); }
    long store(Enterprise company,String name) throws Exception { return data(call("POST","/iam/departments",root,company.id(),Map.of("name",name,"code",name,"type","STORE","sortOrder",0,"status",1)),200).asLong(); }
    long order(Enterprise company,long store,String number,String status,String name,String time,String total,String paid) {
        return repo.insert("INSERT INTO biz_order(tenant_id,department_id,order_no,status,consumption_type,customer_name,customer_phone,customer_code,order_time,total_amount,paid_amount) VALUES(?,?,?,?,'SERVICE',?,'13700000001','VIP001',?,?,?)",company.id(),store,number,status,name,time,total,paid);
    }
    @BeforeEach void setup() throws Exception {
        root=data(call("POST","/user/login",null,null,Map.of("username","admin","password","OrderPlatform123!")),200).path("token").asText();
        a=enterprise("orders-a"); b=enterprise("orders-b");
        storeA=store(a,"A1"); storeA2=store(a,"A2"); storeB=store(b,"B1");
        first=order(a,storeA,"O001","CONFIRMED","张女士","2026-09-22 10:00:00","100.30","60.10");
        second=order(a,storeA2,"O002","CONFIRMED","李女士","2026-09-21 10:00:00","55.00","55.00");
        nextDay=order(a,storeA,"O003","CONFIRMED","100%_VIP","2026-09-23 00:00:00","0.30","0.10");
        pending=order(a,storeA,"P001","PENDING","待确认顾客","2026-09-22 10:00:00","50","0");
        foreign=order(b,storeB,"O001","CONFIRMED","其他企业顾客","2026-09-22 11:00:00","88","88");
        repo.update("INSERT INTO biz_order_item(tenant_id,order_id,consumption_type,name,amount,unit_price) VALUES(?,?,'SERVICE','面部护理',100,100),(?,?,'PRODUCT','体验产品',0.30,0.30)",a.id(),first,a.id(),first);
        repo.update("INSERT INTO biz_order_staff(tenant_id,order_id,staff_name,staff_phone,staff_code) VALUES(?,?,'美容师王','13900000002','EMP001')",a.id(),first);
        repo.update("INSERT INTO biz_order_payment(tenant_id,order_id,method,category,amount) VALUES(?,?,'WECHAT','RECEIPT',50),(?,?,'CASH','RECEIPT',10.10)",a.id(),first,a.id(),first);
    }
    @Test void threeTabsHaveCorrectRecordsAndExactDecimalMoney() throws Exception {
        assertThat(get("/orders").path("total").asInt()).isEqualTo(3);
        var held=get("/orders?tab=pending"); assertThat(held.path("total").asInt()).isEqualTo(1); assertThat(held.path("records").get(0).path("id").asLong()).isEqualTo(pending);
        assertThat(get("/orders?tab=balance").path("total").asInt()).isEqualTo(2);
        var row=get("/orders/"+first);
        assertThat(row.path("totalAmount").asText()).isEqualTo("100.30"); assertThat(row.path("outstandingAmount").asText()).isEqualTo("40.20");
        assertThat(get("/orders/"+nextDay).path("outstandingAmount").asText()).isEqualTo("0.20");
        assertThat(row.path("items").size()).isEqualTo(2); assertThat(row.path("payments").size()).isEqualTo(2); assertThat(row.path("staff").get(0).path("staffName").asText()).isEqualTo("美容师王");
        assertThat(row.path("canVerify").asBoolean()).isTrue();
    }
    @Test void searchDateStoreTypePaymentAndSortingAreServerSideAndDoNotDuplicateMixedOrders() throws Exception {
        assertThat(get("/orders?searchType=ORDER&keyword=O001").path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?searchType=STAFF&keyword=EMP001").path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?searchType=CUSTOMER&keyword=VIP001").path("total").asInt()).isEqualTo(3);
        var literal=mvc.perform(MockMvcRequestBuilders.get("/api/orders").contextPath("/api").header("Authorization","Bearer "+a.token()).param("keyword","%_")).andReturn();
        assertThat(data(literal,200).path("records").get(0).path("id").asLong()).isEqualTo(nextDay); assertThat(data(literal,200).path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?startDate=2026-09-22&endDate=2026-09-22").path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?storeId="+storeA2).path("total").asInt()).isEqualTo(1);
        for (String filter:List.of("paymentMethod=RECEIPT","paymentMethod=WECHAT","consumptionType=PRODUCT")) assertThat(get("/orders?"+filter).path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?paymentMethod=CARD_CONSUMPTION").path("total").asInt()).isZero();
        var page=get("/orders?sortBy=orderNo&sortDirection=asc&page=2&pageSize=1"); assertThat(page.path("total").asInt()).isEqualTo(3); assertThat(page.path("records").get(0).path("id").asLong()).isEqualTo(second);
        assertThat(get("/orders?sortBy=orderTime&sortDirection=asc&pageSize=1").path("records").get(0).path("id").asLong()).isEqualTo(second);
    }
    @Test void platformAggregateAndEnterpriseScopeCannotLeakOtherEnterprises() throws Exception {
        assertThat(data(call("GET","/orders",root,null,null),200).path("total").asInt()).isEqualTo(4);
        var targeted=data(call("GET","/orders",root,b.id(),null),200); assertThat(targeted.path("total").asInt()).isEqualTo(1); assertThat(targeted.toString()).contains("orders-b").doesNotContain("orders-a");
        data(call("GET","/orders/"+foreign,a.token(),null,null),404);
        data(call("GET","/orders?storeId="+storeB,a.token(),null,null),404);
        data(call("GET","/orders",a.token(),b.id(),null),403);
        data(call("GET","/orders/stores",a.token(),b.id(),null),403);
        data(call("GET","/orders",null,null,null),401);
        assertThat(get("/orders/stores").path("total").asInt()).isEqualTo(2);
        assertThat(data(call("GET","/orders/stores",root,null,null),200).path("total").asInt()).isEqualTo(3);
        assertThat(data(call("GET","/orders/stores?keyword=B1",root,null,null),200).path("records").get(0).path("tenantCode").asText()).isEqualTo("orders-b");
    }
    String staff(List<String> permissions,Long department) throws Exception {
        String name="staff"+UUID.randomUUID().toString().substring(0,8);
        long role=data(call("POST","/iam/roles",a.token(),null,Map.of("code",name.toUpperCase(Locale.ROOT),"name",name,"status",1,"permissionCodes",permissions)),200).asLong();
        Map<String,Object> grant=new HashMap<>(Map.of("roleId",role)); if (department!=null) grant.put("departmentId",department);
        data(call("POST","/iam/users",a.token(),null,Map.of("username",name,"nickname",name,"password",PASS,"status",1,"departmentIds",department==null?List.of():List.of(department),"roleGrants",List.of(grant))),200);
        return login(a.code(),name);
    }
    @Test void branchPermissionsConstrainListsLookupsDetailsAndVerification() throws Exception {
        String clerk=staff(List.of("orders:read","orders:write"),storeA);
        assertThat(data(call("GET","/orders",clerk,null,null),200).path("total").asInt()).isEqualTo(2);
        assertThat(data(call("GET","/orders/stores",clerk,null,null),200).path("total").asInt()).isEqualTo(1);
        data(call("GET","/orders/"+second,clerk,null,null),404);
        data(call("GET","/orders?storeId="+storeA2,clerk,null,null),404);
        data(call("POST","/orders/"+second+"/verification",clerk,null,Map.of("verified",true,"version",0)),404);
        data(call("POST","/orders/"+first+"/verification",clerk,null,Map.of("verified",true,"version",0)),200);
        data(call("GET","/orders/verification-settings",clerk,null,null),403);
        String viewer=staff(List.of("orders:read"),storeA);
        assertThat(data(call("GET","/orders/"+first,viewer,null,null),200).path("canVerify").asBoolean()).isFalse();
        data(call("POST","/orders/"+first+"/verification",viewer,null,Map.of("verified",false,"version",1)),403);
        String noOrders=staff(List.of("home:read"),null);
        data(call("GET","/orders",noOrders,null,null),403); data(call("GET","/orders/options",noOrders,null,null),403);
    }
    Map<String,Object> setting(boolean enabled,boolean recheck,long version) { return Map.of("enabled",enabled,"recheckAfterPerformanceChange",recheck,"version",version); }
    @Test void settingsArePerEnterprisePersistentVersionedAndRequireConcreteWriteScope() throws Exception {
        assertThat(get("/orders/verification-settings").path("enabled").asBoolean()).isTrue();
        var saved=data(call("PUT","/orders/verification-settings",a.token(),null,setting(false,false,0)),200);
        assertThat(saved.path("version").asLong()).isEqualTo(1); assertThat(get("/orders/verification-settings").path("enabled").asBoolean()).isFalse();
        assertThat(data(call("GET","/orders/verification-settings",b.token(),null,null),200).path("enabled").asBoolean()).isTrue();
        data(call("PUT","/orders/verification-settings",a.token(),null,setting(true,true,0)),409);
        data(call("GET","/orders/verification-settings",root,null,null),400); data(call("PUT","/orders/verification-settings",root,null,setting(true,true,0)),400);
        data(call("PUT","/orders/verification-settings",root,a.id(),setting(true,true,1)),200);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_platform_audit WHERE tenant_id=? AND action='ORDER_VERIFICATION_SETTINGS'",a.id())).isEqualTo(1);
        assertThat(repo.count("SELECT COUNT(*) FROM biz_order_audit WHERE tenant_id=? AND action='ORDER_VERIFICATION_SETTINGS'",a.id())).isEqualTo(2);
    }
    @Test void verificationHasOptimisticLockAndFollowsPerformanceRecheckSetting() throws Exception {
        data(call("POST","/orders/"+first+"/verification",a.token(),null,Map.of("verified",true,"version",0)),200);
        assertThat(get("/orders?verification=VERIFIED").path("total").asInt()).isEqualTo(1);
        assertThat(get("/orders?verification=UNVERIFIED").path("total").asInt()).isEqualTo(2);
        data(call("POST","/orders/"+first+"/verification",a.token(),null,Map.of("verified",false,"version",0)),409);
        repo.update("UPDATE biz_order SET performance_version=performance_version+1,version=version+1 WHERE id=?",first);
        assertThat(get("/orders/"+first).path("verified").asBoolean()).isFalse();
        data(call("PUT","/orders/verification-settings",a.token(),null,setting(true,false,0)),200);
        assertThat(get("/orders/"+first).path("verified").asBoolean()).isTrue();
        data(call("POST","/orders/"+first+"/verification",a.token(),null,Map.of("verified",false,"version",2)),200);
        assertThat(get("/orders/"+first).path("verified").asBoolean()).isFalse();
        // Incomplete historical review metadata is unverified rather than omitted from both filters.
        repo.update("UPDATE biz_order SET reviewed_at=CURRENT_TIMESTAMP,reviewed_performance_version=NULL WHERE id=?",second);
        data(call("PUT","/orders/verification-settings",a.token(),null,setting(true,true,1)),200);
        assertThat(get("/orders?verification=UNVERIFIED").path("total").asInt()).isEqualTo(3);
        assertThat(repo.count("SELECT COUNT(*) FROM biz_order_audit WHERE order_id=?",first)).isEqualTo(2);
    }
    @Test void disabledVerificationPendingOrdersAndCrossTenantMutationsAreRejected() throws Exception {
        data(call("POST","/orders/"+pending+"/verification",a.token(),null,Map.of("verified",true,"version",0)),409);
        data(call("POST","/orders/"+foreign+"/verification",a.token(),null,Map.of("verified",true,"version",0)),404);
        data(call("POST","/orders/"+first+"/verification",root,null,Map.of("verified",true,"version",0)),400);
        data(call("PUT","/orders/verification-settings",a.token(),null,setting(false,true,0)),200);
        data(call("POST","/orders/"+first+"/verification",a.token(),null,Map.of("verified",true,"version",0)),409);
        assertThat(get("/orders/"+first).path("canVerify").asBoolean()).isFalse();
        data(call("PUT","/platform/tenants/"+a.id(),root,null,Map.of("name","停用企业","status",0)),200);
        assertThat(data(call("GET","/orders",root,a.id(),null),200).path("total").asInt()).isEqualTo(3);
        data(call("PUT","/orders/verification-settings",root,a.id(),setting(true,true,1)),409);
        data(call("POST","/orders/"+first+"/verification",root,a.id(),Map.of("verified",true,"version",0)),409);
        data(call("GET","/orders",a.token(),null,null),401);
    }
    @Test void invalidParametersNeverReachDynamicSqlAndMutationBodiesAreValidated() throws Exception {
        for (String query:List.of("tab=invalid","searchType=bad","sortBy=id","sortDirection=drop","page=0","pageSize=101","storeId=-1","storeId=abc","startDate=wrong","startDate=2026-09-22","startDate=2026-09-23&endDate=2026-09-22","startDate=2026-09-22&endDate=9999-12-31","consumptionType=bad","paymentMethod=bad","verification=bad")) data(call("GET","/orders?"+query,a.token(),null,null),400);
        data(call("PUT","/orders/verification-settings",a.token(),null,Map.of("enabled",true)),400);
        data(call("POST","/orders/"+first+"/verification",a.token(),null,Map.of("verified",true,"version",-1)),400);
        data(call("GET","/orders/stores?pageSize=101",a.token(),null,null),400);
    }
    @Test void logicalAssociationsRejectContaminatedChildrenAndRetainHistoricalStores() throws Exception {
        repo.update("INSERT INTO biz_order_item(tenant_id,order_id,consumption_type,name) VALUES(?,?,'PRODUCT','错误租户记录')",b.id(),first);
        assertThat(get("/orders/"+first).toString()).doesNotContain("错误租户记录");
        data(call("DELETE","/iam/departments/"+storeA,a.token(),null,null),409);
        data(call("PUT","/iam/departments/"+storeA,a.token(),null,Map.of("name","停用门店","code","A1","type","STORE","sortOrder",0,"status",0)),200);
        assertThat(get("/orders/stores").path("total").asInt()).isEqualTo(2);
        assertThat(get("/orders?storeId="+storeA).path("total").asInt()).isEqualTo(2);
        data(call("PUT","/iam/departments/"+storeA,a.token(),null,Map.of("name","错误类型","code","A1","type","DEPARTMENT","sortOrder",0,"status",0)),409);
    }
    @Test void catalogPermissionsAndTablesAreProvisionedWithoutForeignKeys() throws Exception {
        var options=get("/orders/options"); assertThat(options.path("consumptionTypes").size()).isEqualTo(15); assertThat(options.path("paymentMethods").size()).isEqualTo(24);
        assertThat(get("/iam/me").path("permissions").toString()).contains("orders:read","orders:write","order-settings:read","order-settings:write");
        assertThat(repo.count("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.tenant_id=? AND r.code='ADMIN'",a.id())).isEqualTo(repo.count("SELECT COUNT(*) FROM sys_permission"));
        try (var connection=source.getConnection()) {
            for (String table:List.of("biz_order","biz_order_item","biz_order_staff","biz_order_payment","biz_order_verification_setting","biz_order_audit")) {
                try (var keys=connection.getMetaData().getImportedKeys(null,null,table)) { assertThat(keys.next()).isFalse(); }
            }
        }
    }
}
