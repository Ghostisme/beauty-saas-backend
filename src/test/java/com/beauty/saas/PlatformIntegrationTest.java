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
import org.springframework.transaction.annotation.Propagation;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:platform_iam;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "jwt.secret=platform-integration-test-isolated-signing-key-123456789","platform.provisioning-key=",
    "platform.bootstrap-password=PlatformTest123!"})
@AutoConfigureMockMvc
@Transactional
class PlatformIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;
    static final String ROOT_PASS="PlatformTest123!", COMPANY_PASS="CompanyTest123!";
    String root;
    record Enterprise(long id,long admin,String code,String token) {}
    @BeforeEach void loginRoot() throws Exception { root=data(call("POST","/user/login",null,null,Map.of("username","admin","password",ROOT_PASS)),200).path("token").asText(); }
    MvcResult call(String method,String path,String token,Long tenant,Object body) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api"+path).contextPath("/api");
        if (token!=null) request.header("Authorization","Bearer "+token);
        if (tenant!=null) request.header("X-Tenant-Id",tenant);
        if (body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request).andReturn();
    }
    JsonNode data(MvcResult result,int status) throws Exception {
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    Map<String,Object> creation(String code) { return Map.of("code",code,"name","企业 "+code,"adminName","负责人","adminUsername","admin","adminPassword",COMPANY_PASS); }
    Enterprise create(String code) throws Exception {
        var response=data(call("POST","/platform/tenants",root,null,creation(code)),200);
        long tenant=response.path("tenantId").asLong(),admin=response.path("adminUserId").asLong();
        String token=data(call("POST","/user/login",null,null,Map.of("loginType","TENANT","tenantCode",code,"username","admin","password",COMPANY_PASS)),200).path("token").asText();
        return new Enterprise(tenant,admin,code,token);
    }
    long department(Enterprise enterprise,String code) throws Exception {
        return data(call("POST","/iam/departments",root,enterprise.id(),Map.of("name",code,"code",code,"type","STORE","sortOrder",0,"status",1)),200).asLong();
    }
    @Test void platformLoginIsAnIndependentIdentityAndDoesNotRequireEnterpriseCode() throws Exception {
        var me=data(call("GET","/iam/me",root,null,null),200);
        assertThat(me.path("platformAdmin").asBoolean()).isTrue(); assertThat(me.path("tenantId").asLong()).isZero();
        assertThat(me.path("tenantCode").asText()).isEmpty(); assertThat(me.path("owner").asBoolean()).isFalse();
        assertThat(repo.count("SELECT COUNT(*) FROM sys_tenant_admin WHERE user_id=?",me.path("id").asLong())).isZero();
        var a=create("login-company");
        assertThat(data(call("GET","/iam/me",a.token(),null,null),200).path("platformAdmin").asBoolean()).isFalse();
        data(call("POST","/user/login",null,null,Map.of("loginType","PLATFORM","username","admin","password",COMPANY_PASS)),401);
        data(call("POST","/user/login",null,null,Map.of("loginType","TENANT","tenantCode",a.code(),"username","admin","password",ROOT_PASS)),401);
        data(call("POST","/user/login",null,null,Map.of("loginType","PLATFORM","tenantCode",a.code(),"username","admin","password",ROOT_PASS)),400);
    }
    @Test void superAdminCreatesEnterpriseAndAdminInOneTransactionWithoutPlatformKey() throws Exception {
        var input=new HashMap<>(creation("custom-company")); input.put("adminUsername","boss.liu");
        var created=data(call("POST","/platform/tenants",root,null,input),200);
        assertThat(created.path("adminUsername").asText()).isEqualTo("boss.liu");
        long tenant=created.path("tenantId").asLong();
        assertThat(repo.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id=?",tenant)).isEqualTo(7);
        var login=data(call("POST","/user/login",null,null,Map.of("tenantCode","custom-company","username","boss.liu","password",COMPANY_PASS)),200);
        assertThat(login.path("userInfo").path("owner").asBoolean()).isTrue();
        assertThat(login.path("userInfo").path("platformAdmin").asBoolean()).isFalse();
        assertThat(repo.count("SELECT COUNT(*) FROM sys_platform_audit WHERE tenant_id=? AND action='CREATE_ENTERPRISE'",tenant)).isEqualTo(1);
        assertThat(repo.rows("SELECT * FROM sys_platform_audit").toString()).doesNotContain(COMPANY_PASS,ROOT_PASS);
    }
    @Test void enterpriseAdminCannotListCreateOrOperatePlatformResourcesEvenWithForgedTenantHeader() throws Exception {
        var a=create("boundary-a"); var b=create("boundary-b");
        for (String path:List.of("/platform/tenants","/platform/summary","/platform/data/users","/platform/data/roles","/platform/tenants/"+b.id())) data(call("GET",path,a.token(),b.id(),null),403);
        data(call("POST","/platform/tenants",a.token(),null,creation("not-allowed")),403);
        data(call("PUT","/platform/tenants/"+b.id(),a.token(),null,Map.of("name","bad","status",0)),403);
        data(call("POST","/platform/tenants/"+b.id()+"/admin/password",a.token(),null,Map.of("password","Different123!")),403);
        data(call("GET","/iam/users",a.token(),b.id(),null),403);
        data(call("GET","/iam/options",a.token(),b.id(),null),403);
        data(call("GET","/platform/tenants",null,null,null),401);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_platform_admin")).isEqualTo(1);
    }
    @Test void platformSeesAllEnterprisesAndAllBusinessDataWithTenantLabelsAndPagination() throws Exception {
        var a=create("global-a"); var b=create("global-b");
        long first=department(a,"first"),second=department(b,"second");
        for (var item:List.of(Map.entry(a,first),Map.entry(b,second))) data(call("POST","/iam/rooms",root,item.getKey().id(),Map.of("departmentId",item.getValue(),"name","护理室","code","CARE","capacity",1,"status",1)),200);
        assertThat(data(call("GET","/platform/data/users",root,null,null),200).path("total").asInt()).isEqualTo(2);
        assertThat(data(call("GET","/platform/data/roles",root,null,null),200).path("total").asInt()).isEqualTo(14);
        var all=data(call("GET","/platform/data/rooms",root,null,null),200);
        assertThat(all.path("total").asInt()).isEqualTo(2); assertThat(all.toString()).contains("global-a","global-b");
        var filtered=data(call("GET","/platform/data/users?tenantId="+a.id(),root,null,null),200);
        assertThat(filtered.path("total").asInt()).isEqualTo(1); assertThat(filtered.toString()).contains("global-a").doesNotContain("global-b","password","authVersion");
        assertThat(data(call("GET","/platform/data/departments?pageSize=1",root,null,null),200).path("records").size()).isEqualTo(1);
        assertThat(data(call("GET","/platform/tenants?keyword=global-a",root,null,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/platform/summary",root,null,null),200).path("tenants").asInt()).isEqualTo(2);
        data(call("GET","/platform/data/passwords",root,null,null),404); data(call("GET","/platform/tenants?pageSize=101",root,null,null),400);
    }
    @Test void platformMustSelectEnterpriseBeforeWritingAndCannotMixTheirAssociations() throws Exception {
        var a=create("context-a"); var b=create("context-b"); long other=department(b,"other");
        data(call("GET","/iam/users",root,null,null),400);
        data(call("GET","/iam/users",root,999999L,null),404);
        data(call("POST","/iam/rooms",root,a.id(),Map.of("departmentId",other,"code","BAD","name","bad","capacity",1,"status",1)),404);
        assertThat(data(call("GET","/iam/options",root,b.id(),null),200).path("departments").size()).isEqualTo(1);
        var me=data(call("GET","/iam/me",root,a.id(),null),200); assertThat(me.path("tenantId").asLong()).isZero();
        assertThat(me.path("platformAdmin").asBoolean()).isTrue();
    }
    @Test void retainedEnterpriseWithoutOwnerCanBeAssignedANewAdminButPlatformAccountIsNeverReused() throws Exception {
        var a=create("pending-admin");
        repo.update("DELETE FROM sys_tenant_admin WHERE tenant_id=?",a.id()); repo.update("DELETE FROM sys_user_role WHERE user_id=?",a.admin()); repo.update("DELETE FROM sys_user WHERE id=?",a.admin());
        assertThat(data(call("GET","/platform/tenants/"+a.id(),root,null,null),200).path("adminUsername").isMissingNode()).isTrue();
        var input=Map.of("username","admin","nickname","新负责人","password",COMPANY_PASS);
        long user=data(call("POST","/platform/tenants/"+a.id()+"/admin",root,null,input),200).asLong();
        assertThat(user).isNotEqualTo(repo.count("SELECT user_id FROM sys_platform_admin"));
        data(call("POST","/platform/tenants/"+a.id()+"/admin",root,null,input),409);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND user_id=?",a.id(),user)).isEqualTo(1);
    }
    @Test void disabledEnterpriseCannotLoginButPlatformStillSeesItsDataAndEnablingDoesNotReviveOldTokens() throws Exception {
        var a=create("status-company");
        data(call("PUT","/platform/tenants/"+a.id(),root,null,Map.of("name","已停用企业","status",0)),200);
        data(call("GET","/iam/me",a.token(),null,null),401);
        assertThat(data(call("GET","/platform/data/users?tenantId="+a.id(),root,null,null),200).path("total").asInt()).isEqualTo(1);
        data(call("POST","/iam/departments",root,a.id(),Map.of("name","bad","code","BAD","type","STORE","sortOrder",0,"status",1)),409);
        data(call("PUT","/platform/tenants/"+a.id(),root,null,Map.of("name","恢复企业","status",1)),200);
        data(call("GET","/iam/me",a.token(),null,null),401);
        data(call("POST","/user/login",null,null,Map.of("tenantCode",a.code(),"username","admin","password",COMPANY_PASS)),200);
    }
    @Test void platformCanResetEnterpriseAdminAndChangeItsOwnPasswordWithSessionRevocation() throws Exception {
        var a=create("reset-company");
        data(call("POST","/platform/tenants/"+a.id()+"/admin/password",root,null,Map.of("password","ChangedCompany123!")),200);
        data(call("GET","/iam/me",a.token(),null,null),401);
        data(call("POST","/user/login",null,null,Map.of("tenantCode",a.code(),"username","admin","password","ChangedCompany123!")),200);
        data(call("POST","/iam/password",root,null,Map.of("currentPassword",ROOT_PASS,"newPassword","ChangedPlatform123!")),200);
        data(call("GET","/platform/summary",root,null,null),401);
        data(call("POST","/user/login",null,null,Map.of("username","admin","password","ChangedPlatform123!")),200);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void invalidEnterpriseProvisioningRollsBackBusinessAndAuditRows() throws Exception {
        String code="bad-bytes-"+UUID.randomUUID().toString().substring(0,8);
        var input=new HashMap<>(creation(code)); input.put("adminPassword","密".repeat(25));
        data(call("POST","/platform/tenants",root,null,input),400);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_tenant WHERE code=?",code)).isZero();
        assertThat(repo.count("SELECT COUNT(*) FROM sys_platform_audit WHERE detail LIKE ?","%"+code+"%")).isZero();
    }
}
