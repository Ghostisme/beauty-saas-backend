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
import java.time.LocalTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:staff_attendance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=test-signing-key-for-attendance-suite-123456789",
    "platform.provisioning-key=test-platform-key-never-used-in-production"
})
@AutoConfigureMockMvc
@Transactional
class StaffAttendanceIntegrationTest {
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
        String code = "attendance-" + UUID.randomUUID().toString().substring(0, 8);
        var tenant = data(mvc.perform(MockMvcRequestBuilders.post("/api/platform/tenants").contextPath("/api")
            .header("X-Platform-Key", "test-platform-key-never-used-in-production")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("code", code, "name", "考勤企业", "adminName", "负责人", "adminPassword", "TenantTest123!")))).andReturn(), 200);
        var login = data(call("POST", "/user/login", null, Map.of("tenantCode", code, "username", "admin", "password", "TenantTest123!")), 200);
        return new Account(tenant.path("tenantId").asLong(), login.path("token").asText());
    }

    long store(Account account, String code) throws Exception {
        return data(call("POST", "/iam/departments", account.token(), Map.of("code", code, "name", code, "type", "STORE", "sortOrder", 0, "status", 1)), 200).asLong();
    }

    @Test void attendanceRulesValidateScopeConflictAndTenantIsolation() throws Exception {
        var account = provision();
        var foreign = provision();
        long store = store(account, "attendance-store");
        long foreignStore = store(foreign, "foreign-store");
        long employeeRole = repo.count("SELECT id FROM sys_role WHERE tenant_id=? AND code='EMPLOYEE'", account.tenantId());
        long employee = data(call("POST", "/iam/users", account.token(), Map.of("username", "attendance-user", "nickname", "考勤员工", "password", "TenantTest123!", "status", 1,
            "departmentIds", List.of(store), "roleGrants", List.of(Map.of("roleId", employeeRole, "departmentId", store)))), 200).asLong();
        assertThat(data(call("GET", "/staff/attendance/stores", account.token(), null), 200).toString()).contains("attendance-store");
        assertThat(data(call("GET", "/staff/attendance/staff", account.token(), null), 200).toString()).contains("考勤员工");
        var workdays = new ArrayList<Map<String, Object>>();
        for (int day = 1; day <= 7; day++) workdays.add(Map.of("weekday", day, "enabled", day <= 5, "start", "09:00", "end", "18:00"));
        var rule = new HashMap<String, Object>();
        rule.put("name", "中心店考勤"); rule.put("attendanceType", "FIXED"); rule.put("allStores", false); rule.put("storeIds", List.of(store));
        rule.put("allEmployees", false); rule.put("userIds", List.of(employee)); rule.put("workdays", workdays);
        rule.put("overtimeEnabled", true); rule.put("overtimeMode", "AFTER_END"); rule.put("overtimeMinutes", 60); rule.put("overtimeNonworkday", true);
        rule.put("punchMethod", "LOCATION"); rule.put("radiusMeters", 100); rule.put("locations", List.of()); rule.put("wifis", List.of()); rule.put("force", false);
        long ruleId = data(call("POST", "/staff/attendance/rules", account.token(), rule), 200).asLong();
        assertThat(data(call("GET", "/staff/attendance/rules", account.token(), null), 200).get(0).path("name").asText()).isEqualTo("中心店考勤");
        var conflicts = data(call("POST", "/staff/attendance/rules/conflicts", account.token(), new HashMap<>(rule) {{ put("name", "新考勤方案"); }}), 200);
        assertThat(conflicts).hasSize(1);
        var wifi = new HashMap<>(rule); wifi.put("name", "空 WIFI"); wifi.put("punchMethod", "WIFI");
        data(call("POST", "/staff/attendance/rules", account.token(), wifi), 400);
        data(call("GET", "/staff/attendance/rules", foreign.token(), null), 200).isEmpty();
        var foreignRule = new HashMap<>(rule); foreignRule.put("name", "异企业规则"); foreignRule.put("storeIds", List.of(foreignStore)); foreignRule.put("userIds", List.of()); foreignRule.put("allEmployees", true);
        data(call("POST", "/staff/attendance/rules", foreign.token(), foreignRule), 200);
        data(call("PUT", "/staff/attendance/rules/" + ruleId, foreign.token(), rule), 404);
        data(call("DELETE", "/staff/attendance/rules/" + ruleId, account.token(), null), 200);
        assertThat(data(call("GET", "/staff/attendance/rules", account.token(), null), 200)).isEmpty();
    }
}
