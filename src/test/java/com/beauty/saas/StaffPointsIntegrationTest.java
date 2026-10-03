package com.beauty.saas;

import com.beauty.saas.iam.IamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:staff_points;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=test-signing-key-for-staff-points-suite-123456789",
    "platform.provisioning-key=test-platform-key-never-used-in-production"
})
@AutoConfigureMockMvc
@Transactional
class StaffPointsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IamRepository repo;

    record Account(long tenantId, String token) {}

    MvcResult call(String method, String path, String token, Object input) throws Exception {
        var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api" + path).contextPath("/api");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (input != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input));
        return mvc.perform(builder).andReturn();
    }

    JsonNode data(MvcResult response, int status) throws Exception {
        assertThat(response.getResponse().getStatus()).withFailMessage(response.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    }

    Account provision() throws Exception {
        String code = "points-" + UUID.randomUUID().toString().substring(0, 8);
        var tenant = data(mvc.perform(MockMvcRequestBuilders.post("/api/platform/tenants").contextPath("/api")
            .header("X-Platform-Key", "test-platform-key-never-used-in-production")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("code", code, "name", "积分企业", "adminName", "负责人", "adminPassword", "TenantTest123!")))).andReturn(), 200);
        var login = data(call("POST", "/user/login", null, Map.of("tenantCode", code, "username", "admin", "password", "TenantTest123!")), 200);
        return new Account(tenant.path("tenantId").asLong(), login.path("token").asText());
    }

    long store(Account account, String code) throws Exception {
        return data(call("POST", "/iam/departments", account.token(), Map.of("code", code, "name", code, "type", "STORE", "sortOrder", 0, "status", 1)), 200).asLong();
    }

    @Test void pointAdjustmentsCalculateBalanceAndRemainTenantScoped() throws Exception {
        var account = provision();
        var foreign = provision();
        long store = store(account, "points-store");
        long foreignStore = store(foreign, "foreign-points-store");
        long employeeRole = repo.count("SELECT id FROM sys_role WHERE tenant_id=? AND code='EMPLOYEE'", account.tenantId());
        long employee = data(call("POST", "/iam/users", account.token(), Map.of("username", "points-user", "nickname", "小杨", "password", "TenantTest123!", "status", 1,
            "departmentIds", List.of(store), "roleGrants", List.of(Map.of("roleId", employeeRole, "departmentId", store)))), 200).asLong();

        assertThat(data(call("GET", "/staff/points/stores", account.token(), null), 200).toString()).contains("points-store");
        assertThat(data(call("GET", "/staff/points/staff", account.token(), null), 200).toString()).contains("小杨");

        String effectiveDate = LocalDate.now().minusDays(2).toString();
        long add = data(call("POST", "/staff/points", account.token(), Map.of("userId", employee, "pointType", "ADD", "points", 5, "reason", "完成任务", "effectiveDate", effectiveDate)), 200).asLong();
        long deduct = data(call("POST", "/staff/points", account.token(), Map.of("userId", employee, "pointType", "DEDUCT", "points", 2, "reason", "迟到")), 200).asLong();
        var page = data(call("GET", "/staff/points/records?page=1&pageSize=10", account.token(), null), 200);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("records").get(0).path("currentPoints").asLong()).isEqualTo(3);

        data(call("PUT", "/staff/points/" + add, account.token(), Map.of("userId", employee, "pointType", "ADD", "points", 8, "reason", "完成更多任务")), 200);
        page = data(call("GET", "/staff/points/records?storeId=" + store, account.token(), null), 200);
        assertThat(page.path("records").get(0).path("currentPoints").asLong()).isEqualTo(6);
        for (JsonNode record : page.path("records")) {
            if (record.path("id").asLong() == add) assertThat(record.path("effectiveDate").asText()).isEqualTo(effectiveDate);
        }

        data(call("DELETE", "/staff/points/" + deduct, account.token(), null), 200);
        page = data(call("GET", "/staff/points/records", account.token(), null), 200);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("records").get(0).path("currentPoints").asLong()).isEqualTo(8);

        var foreignInput = Map.of("userId", employee, "pointType", "ADD", "points", 1, "reason", "越权");
        data(call("POST", "/staff/points", foreign.token(), foreignInput), 404);
        data(call("GET", "/staff/points/records?storeId=" + foreignStore, account.token(), null), 404);
    }
}
