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
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:staff_management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=test-signing-key-for-isolated-integration-suite-123456789",
    "platform.provisioning-key=test-platform-key-never-used-in-production"
})
@AutoConfigureMockMvc
@Transactional
class StaffManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;

    record Account(long tenantId, String token) {}

    MvcResult call(String method,String path,String token,Object input) throws Exception {
        var builder=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api"+path).contextPath("/api");
        if (token!=null) builder.header("Authorization","Bearer "+token);
        if (input!=null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input));
        return mvc.perform(builder).andReturn();
    }

    JsonNode data(MvcResult response,int status) throws Exception {
        assertThat(response.getResponse().getStatus()).withFailMessage(response.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    }

    Account provision() throws Exception {
        String code="staff-"+UUID.randomUUID().toString().substring(0,8);
        var tenant=data(mvc.perform(MockMvcRequestBuilders.post("/api/platform/tenants").contextPath("/api")
            .header("X-Platform-Key","test-platform-key-never-used-in-production")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("code",code,"name","测试企业","adminName","负责人","adminPassword","TenantTest123!")))).andReturn(),200);
        var login=data(call("POST","/user/login",null,Map.of("tenantCode",code,"username","admin","password","TenantTest123!")),200);
        return new Account(tenant.path("tenantId").asLong(),login.path("token").asText());
    }

    long store(Account account,String code) throws Exception {
        return data(call("POST","/iam/departments",account.token(),Map.of("code",code,"name",code,"type","STORE","sortOrder",0,"status",1)),200).asLong();
    }

    @Test void positionCrudIsTenantScopedAndReferencedPositionsCannotBeRemoved() throws Exception {
        var a=provision(); var b=provision();
        assertThat(data(call("GET","/staff/positions",a.token(),null),200).size()).isEqualTo(5);
        long position=data(call("POST","/staff/positions",a.token(),Map.of("name","顾问","code","ADVISOR","status",1)),200).asLong();
        data(call("POST","/staff/positions",a.token(),Map.of("name","顾问","status",1)),409);
        data(call("PUT","/staff/positions/"+position,b.token(),Map.of("name","越权","status",1)),404);
        long store=store(a,"first-store");
        long employeeRole=repo.count("SELECT id FROM sys_role WHERE tenant_id=? AND code='EMPLOYEE'",a.tenantId());
        var user=Map.of("username","employee","nickname","员工","password","TenantTest123!","status",1,
            "departmentIds",List.of(store),"roleGrants",List.of(Map.of("roleId",employeeRole,"departmentId",store)),"positionId",position);
        long userId=data(call("POST","/iam/users",a.token(),user),200).asLong();
        assertThat(data(call("GET","/iam/users",a.token(),null),200).path("records").get(0).path("positionName").asText()).isEqualTo("顾问");
        data(call("DELETE","/staff/positions/"+position,a.token(),null),409);
        data(call("PUT","/staff/positions/"+position,a.token(),Map.of("name","顾问","status",0)),409);
        data(call("DELETE","/iam/users/"+userId,a.token(),null),200);
        data(call("DELETE","/staff/positions/"+position,a.token(),null),200);
    }

    @Test void shiftPeriodsParticipantsAssignmentsAndCopyAreValidated() throws Exception {
        var a=provision(); var b=provision();
        long store=store(a,"first-store"), otherStore=store(a,"second-store"), foreignStore=store(b,"foreign-store");
        var invalid=Map.of("name","重叠班次","color","#FF9000","storeIds",List.of(store),
            "periods",List.of(Map.of("start","09:00","end","12:00"),Map.of("start","11:00","end","18:00")));
        data(call("POST","/staff/scheduling/shifts",a.token(),invalid),400);
        data(call("POST","/staff/scheduling/shifts",a.token(),Map.of("name","越权","color","#FF9000","storeIds",List.of(foreignStore),
            "periods",List.of(Map.of("start","09:00","end","18:00")))),404);
        var shift=Map.of("name","早班","color","#FF9000","storeIds",List.of(store),
            "periods",List.of(Map.of("start","08:30","end","12:00"),Map.of("start","13:00","end","18:00")));
        long shiftId=data(call("POST","/staff/scheduling/shifts",a.token(),shift),200).asLong();
        assertThat(data(call("GET","/staff/scheduling/shifts",a.token(),null),200).get(0).path("periods").size()).isEqualTo(2);
        data(call("DELETE","/iam/departments/"+store,a.token(),null),409);
        long role=repo.count("SELECT id FROM sys_role WHERE tenant_id=? AND code='EMPLOYEE'",a.tenantId());
        long userId=data(call("POST","/iam/users",a.token(),Map.of("username","employee","nickname","员工","password","TenantTest123!","status",1,
            "departmentIds",List.of(store),"roleGrants",List.of(Map.of("roleId",role,"departmentId",store)))),200).asLong();
        var date=LocalDate.now().with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
        var assignment=Map.of("storeId",store,"userId",userId,"date",date.toString(),"shiftId",shiftId);
        data(call("PUT","/staff/scheduling/assignments",a.token(),assignment),409);
        data(call("PUT","/staff/scheduling/participants",a.token(),Map.of("storeId",store,"userIds",List.of(userId))),200);
        data(call("PUT","/staff/scheduling/assignments",a.token(),assignment),200);
        var calendar=data(call("GET","/staff/scheduling/calendar?storeId="+store+"&startDate="+date+"&endDate="+date,a.token(),null),200);
        assertThat(calendar.path("assignments").size()).isEqualTo(1);
        data(call("DELETE","/staff/scheduling/shifts/"+shiftId,a.token(),null),409);
        data(call("PUT","/staff/scheduling/shifts/"+shiftId,a.token(),Map.of("name","早班","color","#FF9000","storeIds",List.of(otherStore),"periods",List.of(Map.of("start","08:30","end","18:00")))),409);
        int copied=data(call("POST","/staff/scheduling/copy-week",a.token(),Map.of("storeId",store,"startDate",date.plusWeeks(1).toString())),200).asInt();
        assertThat(copied).isEqualTo(1);
        assertThat(data(call("POST","/staff/scheduling/copy-week",a.token(),Map.of("storeId",store,"startDate",date.plusWeeks(1).toString())),200).asInt()).isZero();
        assertThat(data(call("GET","/staff/scheduling/calendar?storeId="+store+"&startDate="+date.plusWeeks(1)+"&endDate="+date.plusWeeks(1),a.token(),null),200).path("assignments").size()).isEqualTo(1);
    }
}
