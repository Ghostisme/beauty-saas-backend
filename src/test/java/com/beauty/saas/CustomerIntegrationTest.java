package com.beauty.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:customers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=customers-integration-test-isolated-signing-key-123456789", "platform.provisioning-key=",
    "platform.bootstrap-password=CustomerPlatform123!"})
@AutoConfigureMockMvc
@Transactional
class CustomerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    static final String PLATFORM_PASS = "CustomerPlatform123!";
    static final String COMPANY_PASS = "CustomerCompany123!";
    String root;
    Enterprise a;
    Enterprise b;
    long storeA;
    record Enterprise(long id, String code, String token) {}

    @BeforeEach
    void setup() throws Exception {
        root = data(call("POST", "/user/login", null, null, Map.of("username", "admin", "password", PLATFORM_PASS)), 200).path("token").asText();
        a = create("customers-a-" + UUID.randomUUID().toString().substring(0, 8));
        b = create("customers-b-" + UUID.randomUUID().toString().substring(0, 8));
        storeA = data(call("POST", "/iam/departments", root, a.id(), Map.of("name", "顾客门店", "code", "CUSTOMER_STORE", "type", "STORE", "sortOrder", 0, "status", 1)), 200).asLong();
    }

    Enterprise create(String code) throws Exception {
        var result = data(call("POST", "/platform/tenants", root, null, Map.of("code", code, "name", "企业" + code, "adminName", "负责人", "adminUsername", "admin", "adminPassword", COMPANY_PASS)), 200);
        String token = data(call("POST", "/user/login", null, null, Map.of("tenantCode", code, "username", "admin", "password", COMPANY_PASS)), 200).path("token").asText();
        return new Enterprise(result.path("tenantId").asLong(), code, token);
    }

    MvcResult call(String method, String path, String token, Long tenant, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api" + path).contextPath("/api");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (tenant != null) request.header("X-Tenant-Id", tenant);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request).andReturn();
    }

    JsonNode data(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    @Test
    void customerDossierSupportsListUpdateDeleteAndTenantIsolation() throws Exception {
        var created = data(call("POST", "/customers", a.token(), null, Map.of("name", "林女士", "phone", "13800002018", "code", "CUS-001", "storeId", storeA)), 200);
        long id = created.asLong();
        var rows = data(call("GET", "/customers?keyword=林&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(rows.path("total").asInt()).isEqualTo(1);
        assertThat(rows.path("records").get(0).path("name").asText()).isEqualTo("林女士");
        assertThat(data(call("GET", "/customers?page=1&pageSize=10", b.token(), null, null), 200).path("total").asInt()).isZero();
        data(call("PUT", "/customers/" + id, a.token(), null, Map.of("name", "林女士升级", "phone", "13800002018", "code", "CUS-001", "storeId", storeA, "tracker", "模拟员工A")), 200);
        assertThat(data(call("GET", "/customers/" + id, a.token(), null, null), 200).path("tracker").asText()).isEqualTo("模拟员工A");
        data(call("POST", "/customers/storage", a.token(), null, Map.of("customerId", id, "storageType", "PRODUCT", "itemName", "护发精油", "quantity", 2.5)), 200);
        var storage = data(call("GET", "/customers/storage?page=1&pageSize=10&keyword=护发", a.token(), null, null), 200);
        assertThat(storage.path("total").asInt()).isEqualTo(1);
        assertThat(storage.path("records").get(0).path("customerId").asLong()).isEqualTo(id);
        data(call("DELETE", "/customers/" + id, b.token(), null, null), 404);
        data(call("DELETE", "/customers/" + id, a.token(), null, null), 200);
        assertThat(data(call("GET", "/customers?page=1&pageSize=10", a.token(), null, null), 200).path("total").asInt()).isZero();
    }

    @Test
    void platformCanReadAllCustomersButMustChooseTenantBeforeWriting() throws Exception {
        long first = data(call("POST", "/customers", a.token(), null, Map.of("name", "甲企业顾客", "phone", "13800002001")), 200).asLong();
        long second = data(call("POST", "/customers", b.token(), null, Map.of("name", "乙企业顾客", "phone", "13800002002")), 200).asLong();
        var all = data(call("GET", "/customers?page=1&pageSize=10", root, null, null), 200);
        assertThat(all.path("total").asInt()).isEqualTo(2);
        assertThat(all.path("records").get(0).path("tenantName").asText()).isNotBlank();
        assertThat(data(call("GET", "/customers/" + second, root, null, null), 200).path("tenantId").asLong()).isEqualTo(b.id());
        assertThat(data(call("GET", "/customers?page=1&pageSize=10", root, a.id(), null), 200).path("total").asInt()).isEqualTo(1);
        data(call("POST", "/customers", root, null, Map.of("name", "禁止无范围写入", "phone", "13800002003")), 400);
        data(call("DELETE", "/customers/" + first, root, b.id(), null), 404);
        data(call("DELETE", "/customers/" + first, root, a.id(), null), 200);
        assertThat(data(call("GET", "/customers?page=1&pageSize=10", root, null, null), 200).path("total").asInt()).isEqualTo(1);
    }
}
