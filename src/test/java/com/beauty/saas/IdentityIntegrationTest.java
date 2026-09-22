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
import org.springframework.util.DigestUtils;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:beauty_saas;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=test-signing-key-for-isolated-integration-suite-123456789",
    "platform.provisioning-key=test-platform-key-never-used-in-production"
})
@AutoConfigureMockMvc
@Transactional
class IdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;
    @Autowired DataSource dataSource;
    private static final String KEY="test-platform-key-never-used-in-production";
    private static final String PASS="TenantTest123!";
    record Account(long tenant,long user,String code,String token) {}
    Account a,b;

    MvcResult call(String method,String path,String token,Object input) throws Exception {
        var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api"+path).contextPath("/api");
        if (token!=null) builder.header("Authorization","Bearer "+token);
        if (input!=null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input));
        return mvc.perform(builder).andReturn();
    }
    JsonNode data(MvcResult result,int status) throws Exception {
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    Account provision(String code) throws Exception {
        var result=mvc.perform(MockMvcRequestBuilders.post("/api/platform/tenants").contextPath("/api").header("X-Platform-Key",KEY)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("code",code,"name","测试企业"+code,"adminName","测试负责人","adminPassword",PASS)))).andReturn();
        var tenant=data(result,200);
        var login=data(call("POST","/user/login",null,Map.of("tenantCode",code,"username","admin","password",PASS)),200);
        return new Account(tenant.path("tenantId").asLong(),tenant.path("adminUserId").asLong(),code,login.path("token").asText());
    }
    @BeforeEach void setup() throws Exception { a=provision("test-a-"+UUID.randomUUID().toString().substring(0,8)); b=provision("test-b-"+UUID.randomUUID().toString().substring(0,8)); }
    long role(Account account,String code) { return repo.count("SELECT id FROM sys_role WHERE tenant_id=? AND code=?",account.tenant(),code); }
    Map<String,Object> department(String code,Long parent) {
        Map<String,Object> value=new LinkedHashMap<>(Map.of("code",code,"name",code,"type","STORE","sortOrder",0,"status",1));
        if (parent!=null) value.put("parentId",parent); return value;
    }
    long department(Account account,String code,Long parent) throws Exception { return data(call("POST","/iam/departments",account.token(),department(code,parent)),200).asLong(); }
    Map<String,Object> user(String name,List<Long> departments,List<Map<String,Object>> roles) {
        return new LinkedHashMap<>(Map.of("username",name,"nickname","测试员工","password",PASS,"status",1,"departmentIds",departments,"roleGrants",roles));
    }
    Map<String,Object> grant(long role,Long department) {
        Map<String,Object> grant=new LinkedHashMap<>(); grant.put("roleId",role); if (department!=null) grant.put("departmentId",department); return grant;
    }
    long createUser(Account account,String name,List<Long> departments,List<Map<String,Object>> roles) throws Exception { return data(call("POST","/iam/users",account.token(),user(name,departments,roles)),200).asLong(); }
    String login(Account account,String username,String password) throws Exception { return data(call("POST","/user/login",null,Map.of("tenantCode",account.code(),"username",username,"password",password)),200).path("token").asText(); }
    Map<String,Object> room(String code,long department) { return new LinkedHashMap<>(Map.of("code",code,"name",code,"departmentId",department,"capacity",2,"status",1)); }
    Map<String,Object> roleInput(String code,List<String> permissions) { return new LinkedHashMap<>(Map.of("code",code,"name",code,"status",1,"permissionCodes",permissions)); }

    @Test void corsAllowsConfiguredFrontendsBeforeAuthenticationAndRejectsOtherOrigins() throws Exception {
        for (String origin : List.of("http://127.0.0.1:5173", "http://localhost:4173")) {
            var login=mvc.perform(MockMvcRequestBuilders.post("/api/user/login").contextPath("/api")
                .header(HttpHeaders.ORIGIN,origin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("tenantCode",a.code(),"username","admin","password",PASS)))).andReturn();
            data(login,200);
            assertThat(login.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(origin);
        }
        var unauthenticated=mvc.perform(MockMvcRequestBuilders.get("/api/iam/me").contextPath("/api")
            .header(HttpHeaders.ORIGIN,"http://127.0.0.1:5173")).andReturn();
        data(unauthenticated,401);
        assertThat(unauthenticated.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://127.0.0.1:5173");
        var preflight=mvc.perform(MockMvcRequestBuilders.options("/api/iam/users").contextPath("/api")
            .header(HttpHeaders.ORIGIN,"http://127.0.0.1:5173")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"GET")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,"Authorization,Content-Type")).andReturn();
        assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
        var rejected=mvc.perform(MockMvcRequestBuilders.get("/api/iam/me").contextPath("/api")
            .header(HttpHeaders.ORIGIN,"https://untrusted.invalid").header(HttpHeaders.AUTHORIZATION,"Bearer "+a.token())).andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(403);
        assertThat(rejected.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test void platformCreatesSeparateAdminsAndSevenRolesWithoutForeignKeys() throws Exception {
        assertThat(a.tenant()).isNotEqualTo(b.tenant());
        assertThat(repo.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id=?",a.tenant())).isEqualTo(7);
        assertThat(repo.one("SELECT password FROM sys_user WHERE tenant_id=? AND id=?",a.tenant(),a.user()).get("password").toString()).startsWith("$2a$");
        data(call("POST","/platform/tenants",a.token(),Map.of("code","illegal-enterprise","name","非法开通","adminName","admin","adminPassword",PASS)),403);
        try (var c=dataSource.getConnection(); var tables=c.getMetaData().getTables(c.getCatalog(),null,"sys_%",new String[]{"TABLE"})) {
            int count=0;
            while (tables.next()) { count++; try (var foreign=c.getMetaData().getImportedKeys(c.getCatalog(),null,tables.getString("TABLE_NAME"))) { assertThat(foreign.next()).isFalse(); } }
            assertThat(count).isEqualTo(13);
        }
    }
    @Test void loginRequiresTenantAndRejectsUntrustedOrMissingTokens() throws Exception {
        data(call("POST","/user/login",null,Map.of("loginType","TENANT","username","admin","password",PASS)),400);
        data(call("POST","/user/login",null,Map.of("tenantCode",a.code(),"username","admin","password","wrong-pass")),401);
        data(call("GET","/iam/users",null,null),401);
        data(call("GET","/iam/users","forged.token.signature",null),401);
        var me=data(call("GET","/iam/me",a.token(),null),200);
        assertThat(me.path("tenantId").asLong()).isEqualTo(a.tenant());
        assertThat(me.path("owner").asBoolean()).isTrue();
        assertThat(me.has("password")).isFalse();
    }
    @Test void tenantCannotReadOrModifyAnotherTenantsRowsOrAssociations() throws Exception {
        long bDepartment=department(b,"store",null);
        long bUser=createUser(b,"same-user",List.of(bDepartment),List.of(grant(role(b,"EMPLOYEE"),bDepartment)));
        long bRoom=data(call("POST","/iam/rooms",b.token(),room("room",bDepartment)),200).asLong();
        data(call("PUT","/iam/departments/"+bDepartment,a.token(),department("renamed",null)),404);
        data(call("DELETE","/iam/users/"+bUser,a.token(),null),404);
        data(call("DELETE","/iam/rooms/"+bRoom,a.token(),null),404);
        data(call("DELETE","/iam/roles/"+role(b,"EMPLOYEE"),a.token(),null),404);
        data(call("POST","/iam/users",a.token(),user("bad-user",List.of(bDepartment),List.of(grant(role(a,"EMPLOYEE"),bDepartment)))),404);
        data(call("POST","/iam/users",a.token(),user("bad-role",List.of(),List.of(grant(role(b,"EMPLOYEE"),null)))),404);
        var users=data(call("GET","/iam/users?tenantId="+b.tenant(),a.token(),null),200);
        assertThat(users.path("total").asInt()).isEqualTo(1);
        assertThat(users.toString()).doesNotContain("same-user");
        assertThat(repo.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id=?",a.tenant())).isEqualTo(1);
    }
    @Test void departmentHierarchyPreventsCyclesAndLinkedDeletion() throws Exception {
        long parent=department(a,"parent",null), child=department(a,"child",parent);
        data(call("PUT","/iam/departments/"+parent,a.token(),department("parent",child)),400);
        data(call("PUT","/iam/departments/"+parent,a.token(),department("parent",parent)),400);
        data(call("DELETE","/iam/departments/"+parent,a.token(),null),409);
        long room=data(call("POST","/iam/rooms",a.token(),room("room",child)),200).asLong();
        data(call("DELETE","/iam/departments/"+child,a.token(),null),409);
        data(call("DELETE","/iam/rooms/"+room,a.token(),null),200);
        data(call("DELETE","/iam/departments/"+child,a.token(),null),200);
        data(call("DELETE","/iam/departments/"+parent,a.token(),null),200);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_department_room WHERE tenant_id=?",a.tenant())).isZero();
    }
    @Test void usersHaveScopedRoleRelationsPaginationAndTransactionalCleanup() throws Exception {
        long department=department(a,"staff-store",null);
        long id=createUser(a,"employee",List.of(department),List.of(grant(role(a,"EMPLOYEE"),department)));
        var page=data(call("GET","/iam/users?page=1&pageSize=1&keyword=employee",a.token(),null),200);
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("records").get(0).path("roleGrants").get(0).path("departmentId").asLong()).isEqualTo(department);
        assertThat(page.toString()).doesNotContain("password", "authVersion");
        data(call("DELETE","/iam/departments/"+department,a.token(),null),409);
        data(call("POST","/iam/users",a.token(),user("duplicate",List.of(department),List.of(grant(role(a,"EMPLOYEE"),department),grant(role(a,"EMPLOYEE"),department)))),400);
        data(call("DELETE","/iam/users/"+id,a.token(),null),200);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND user_id=?",a.tenant(),id)).isZero();
        assertThat(repo.count("SELECT COUNT(*) FROM sys_user_department WHERE tenant_id=? AND user_id=?",a.tenant(),id)).isZero();
        data(call("DELETE","/iam/departments/"+department,a.token(),null),200);
    }
    @Test void ownerCannotBeDisabledDeletedDemotedOrDuplicated() throws Exception {
        data(call("DELETE","/iam/users/"+a.user(),a.token(),null),409);
        var input=user("admin",List.of(),List.of(grant(role(a,"ADMIN"),null))); input.remove("password"); input.put("status",0);
        data(call("PUT","/iam/users/"+a.user(),a.token(),input),409);
        input.put("status",1); input.put("roleGrants",List.of(grant(role(a,"EMPLOYEE"),null)));
        data(call("PUT","/iam/users/"+a.user(),a.token(),input),409);
        data(call("POST","/iam/users",a.token(),user("another-admin",List.of(),List.of(grant(role(a,"ADMIN"),null)))),403);
        data(call("DELETE","/iam/roles/"+role(a,"ADMIN"),a.token(),null),409);
        data(call("PUT","/iam/roles/"+role(a,"ADMIN"),a.token(),roleInput("RENAMED",List.of("home:read"))),409);
    }
    @Test void permissionsAreEnforcedOnServerAndRevokedWithoutWaitingForJwtExpiry() throws Exception {
        long role=data(call("POST","/iam/roles",a.token(),roleInput("READER",List.of("users:read"))),200).asLong();
        createUser(a,"reader",List.of(),List.of(grant(role,null)));
        String token=login(a,"reader",PASS);
        data(call("GET","/iam/users",token,null),200);
        data(call("POST","/iam/departments",token,department("blocked",null)),403);
        data(call("PUT","/iam/roles/"+role,a.token(),roleInput("READER",List.of("home:read"))),200);
        data(call("GET","/iam/users",token,null),403);
        data(call("DELETE","/iam/roles/"+role,a.token(),null),409);
    }
    @Test void storeRoleCannotReadOrMoveRoomsAcrossStores() throws Exception {
        long first=department(a,"first",null), second=department(a,"second",null), child=department(a,"child",first);
        long scopedRole=data(call("POST","/iam/roles",a.token(),roleInput("ROOM_OPERATOR",List.of("rooms:read","rooms:write","departments:read"))),200).asLong();
        createUser(a,"operator",List.of(first),List.of(grant(scopedRole,first)));
        String token=login(a,"operator",PASS);
        long firstRoom=data(call("POST","/iam/rooms",token,room("first-room",child)),200).asLong();
        long secondRoom=data(call("POST","/iam/rooms",a.token(),room("second-room",second)),200).asLong();
        assertThat(data(call("GET","/iam/rooms",token,null),200).path("total").asInt()).isEqualTo(1);
        data(call("POST","/iam/rooms",token,room("denied",second)),403);
        data(call("PUT","/iam/rooms/"+firstRoom,token,room("first-room",second)),403);
        data(call("PUT","/iam/rooms/"+secondRoom,token,room("second-room",first)),403);
        data(call("DELETE","/iam/rooms/"+secondRoom,token,null),403);
    }
    @Test void passwordResetDisableAndSelfChangeInvalidateExistingSessions() throws Exception {
        long user=createUser(a,"employee",List.of(),List.of(grant(role(a,"EMPLOYEE"),null)));
        String token=login(a,"employee",PASS);
        data(call("POST","/iam/users/"+user+"/password",a.token(),Map.of("password","NewPassword123!")),200);
        data(call("GET","/iam/me",token,null),401);
        token=login(a,"employee","NewPassword123!");
        var input=user("employee",List.of(),List.of(grant(role(a,"EMPLOYEE"),null))); input.remove("password"); input.put("status",0);
        data(call("PUT","/iam/users/"+user,a.token(),input),200);
        data(call("GET","/iam/me",token,null),401);
        data(call("POST","/iam/password",a.token(),Map.of("currentPassword",PASS,"newPassword","ChangedOwner123!")),200);
        data(call("GET","/iam/me",a.token(),null),401);
        assertThat(login(a,"admin","ChangedOwner123!")).isNotBlank();
    }
    @Test void paginationValidationAndPerTenantUniqueNames() throws Exception {
        department(a,"same-store",null); department(b,"same-store",null);
        createUser(a,"same-user",List.of(),List.of(grant(role(a,"EMPLOYEE"),null)));
        createUser(b,"same-user",List.of(),List.of(grant(role(b,"EMPLOYEE"),null)));
        data(call("GET","/iam/users?page=0",a.token(),null),400);
        data(call("GET","/iam/users?pageSize=101",a.token(),null),400);
        data(call("GET","/iam/rooms?pageSize=-1",a.token(),null),400);
        data(call("POST","/iam/roles",a.token(),roleInput("INVALID",List.of("users:write"))),400);
    }

    @Test void scopedUserVisibilityAndOptionsNeverExposeAnotherStore() throws Exception {
        long first=department(a,"first",null), other=department(a,"other",null), child=department(a,"child",first);
        long viewer=data(call("POST","/iam/roles",a.token(),roleInput("VIEWER",List.of("users:read","rooms:read","rooms:write"))),200).asLong();
        createUser(a,"store-viewer",List.of(first),List.of(grant(viewer,first)));
        createUser(a,"visible-child",List.of(child),List.of(grant(role(a,"EMPLOYEE"),child)));
        createUser(a,"hidden-other",List.of(other),List.of(grant(role(a,"EMPLOYEE"),other)));
        createUser(a,"shared-staff",List.of(first,other),List.of(grant(role(a,"EMPLOYEE"),first),grant(role(a,"EMPLOYEE"),other)));
        String token=login(a,"store-viewer",PASS);
        var users=data(call("GET","/iam/users",token,null),200);
        assertThat(users.path("total").asInt()).isEqualTo(3);
        assertThat(users.toString()).contains("visible-child").doesNotContain("hidden-other", "admin");
        for (var user : users.path("records")) {
            for (var department : user.path("departments")) assertThat(department.path("id").asLong()).isNotEqualTo(other);
            for (var grant : user.path("roleGrants")) assertThat(grant.path("departmentId").asLong()).isNotEqualTo(other);
        }
        data(call("GET","/iam/users?departmentId="+other,token,null),403);
        assertThat(data(call("GET","/iam/users?departmentId="+first,token,null),200).path("total").asInt()).isEqualTo(2);
        var options=data(call("GET","/iam/options",token,null),200);
        assertThat(options.path("departments").size()).isEqualTo(2);
        assertThat(options.path("roomDepartmentIds").toString()).contains(String.valueOf(first),String.valueOf(child));
        assertThat(options.path("roles").size()).isZero();
        assertThat(options.path("permissions").size()).isZero();
        data(call("GET","/iam/tenant",token,null),403);
    }

    @Test void administratorsCannotGrantInvalidRelationsOrPermissionsTheyDoNotHave() throws Exception {
        long store=department(a,"store",null);
        long writer=data(call("POST","/iam/roles",a.token(),roleInput("USER_EDITOR",List.of("users:read","users:write","roles:read","roles:write"))),200).asLong();
        createUser(a,"limited-admin",List.of(),List.of(grant(writer,null)));
        String token=login(a,"limited-admin",PASS);
        data(call("POST","/iam/roles",token,roleInput("EXCESS",List.of("tenant:read","tenant:write"))),403);
        data(call("POST","/iam/users",a.token(),user("missing-membership",List.of(),List.of(grant(role(a,"EMPLOYEE"),store)))),400);
        data(call("POST","/iam/users",a.token(),user("scoped-company-role",List.of(store),List.of(grant(writer,store)))),400);
        var nullDepartment=user("null-dept",Collections.singletonList(null),List.of(grant(role(a,"EMPLOYEE"),null)));
        data(call("POST","/iam/users",a.token(),nullDepartment),400);
        data(call("POST","/iam/users",a.token(),user("null-role",List.of(),Collections.singletonList(null))),400);
        var inactive=roleInput("INACTIVE",List.of("home:read")); inactive.put("status",0);
        long disabled=data(call("POST","/iam/roles",a.token(),inactive),200).asLong();
        data(call("POST","/iam/users",a.token(),user("inactive-role",List.of(),List.of(grant(disabled,null)))),409);
        data(call("POST","/iam/users/"+a.user()+"/password",token,Map.of("password",PASS)),403);
    }

    @Test void tenantAndRoleStatusChangesTakeEffectWithExistingTokens() throws Exception {
        long viewer=data(call("POST","/iam/roles",a.token(),roleInput("USER_VIEWER",List.of("users:read"))),200).asLong();
        createUser(a,"viewer",List.of(),List.of(grant(viewer,null)));
        String token=login(a,"viewer",PASS);
        var inactive=roleInput("USER_VIEWER",List.of("users:read")); inactive.put("status",0);
        data(call("PUT","/iam/roles/"+viewer,a.token(),inactive),200);
        data(call("GET","/iam/users",token,null),403);
        repo.update("UPDATE sys_tenant SET status=0 WHERE id=?",a.tenant());
        data(call("GET","/iam/me",a.token(),null),401);
        data(call("POST","/user/login",null,Map.of("tenantCode",a.code(),"username","admin","password",PASS)),401);
        data(call("GET","/iam/me",b.token(),null),200);
    }

    @Test void validLegacyPasswordsUpgradeWithoutBreakingExistingAccounts() throws Exception {
        String legacy="old123";
        repo.update("UPDATE sys_user SET password=? WHERE tenant_id=? AND id=?",DigestUtils.md5DigestAsHex(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)),a.tenant(),a.user());
        String token=login(a,"admin",legacy);
        assertThat(repo.one("SELECT password FROM sys_user WHERE tenant_id=? AND id=?",a.tenant(),a.user()).get("password").toString()).startsWith("$2a$");
        data(call("POST","/iam/password",token,Map.of("currentPassword","incorrect","newPassword",PASS)),400);
        data(call("POST","/iam/password",token,Map.of("currentPassword",legacy,"newPassword",PASS)),200);
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void provisioningRollsBackCompletelyWhenPasswordFailsByteValidation() throws Exception {
        String code="rollback-"+UUID.randomUUID().toString().substring(0,8);
        var result=mvc.perform(MockMvcRequestBuilders.post("/api/platform/tenants").contextPath("/api").header("X-Platform-Key",KEY)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("code",code,"name","回滚验证","adminName","管理员","adminPassword","密".repeat(25))))).andReturn();
        data(result,400);
        assertThat(repo.count("SELECT COUNT(*) FROM sys_tenant WHERE code=?",code)).isZero();
    }
}
