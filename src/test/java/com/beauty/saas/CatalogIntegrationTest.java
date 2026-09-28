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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:catalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "jwt.secret=catalog-integration-test-isolated-signing-key-123456789", "platform.provisioning-key=",
    "platform.bootstrap-password=CatalogPlatform123!"})
@AutoConfigureMockMvc
@Transactional
class CatalogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final String PLATFORM_PASS = "CatalogPlatform123!";
    static final String COMPANY_PASS = "CatalogCompany123!";
    String root;
    Enterprise a;
    Enterprise b;
    long storeA;
    long productA;

    record Enterprise(long id, String code, String token) {}

    @BeforeEach
    void setup() throws Exception {
        root = data(call("POST", "/user/login", null, null,
            Map.of("username", "admin", "password", PLATFORM_PASS)), 200).path("token").asText();
        a = create("catalog-a-" + UUID.randomUUID().toString().substring(0, 8));
        b = create("catalog-b-" + UUID.randomUUID().toString().substring(0, 8));
        storeA = data(call("POST", "/iam/departments", root, a.id(),
            Map.of("name", "测试门店", "code", "CATALOG_STORE", "type", "STORE", "sortOrder", 0, "status", 1)), 200).asLong();
        productA = data(call("POST", "/items?kind=PRODUCT", a.token(), null,
            Map.of("code", "SKU-001", "name", "护理精华", "category", "护理", "price", new BigDecimal("128.00"),
                "unit", "瓶", "spec", "30ml", "status", 1)), 200).asLong();
    }

    Enterprise create(String code) throws Exception {
        var result = data(call("POST", "/platform/tenants", root, null,
            Map.of("code", code, "name", "企业" + code, "adminName", "负责人", "adminUsername", "admin", "adminPassword", COMPANY_PASS)), 200);
        String token = data(call("POST", "/user/login", null, null,
            Map.of("tenantCode", code, "username", "admin", "password", COMPANY_PASS)), 200).path("token").asText();
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
    void itemTabsAreTenantScopedAndSupportUpdateAndLogicalDelete() throws Exception {
        var products = data(call("GET", "/items?kind=PRODUCT&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(products.path("total").asInt()).isEqualTo(1);
        assertThat(products.path("records").get(0).path("name").asText()).isEqualTo("护理精华");
        assertThat(data(call("GET", "/items?kind=PRODUCT&page=1&pageSize=10", b.token(), null, null), 200).path("total").asInt()).isZero();
        data(call("PUT", "/items/" + productA + "?kind=PRODUCT", a.token(), null,
            Map.of("code", "SKU-001", "name", "护理精华升级", "price", new BigDecimal("138.00"), "status", 1)), 200);
        assertThat(data(call("GET", "/items?kind=PRODUCT&keyword=升级", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        data(call("DELETE", "/items/" + productA + "?kind=PRODUCT", b.token(), null, null), 404);
        data(call("DELETE", "/items/" + productA + "?kind=PRODUCT", a.token(), null, null), 200);
        assertThat(data(call("GET", "/items?kind=PRODUCT&status=0", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void inventoryLedgerUpdatesBalanceRejectsNegativeStockAndKeepsHistory() throws Exception {
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "IN", "quantity", new BigDecimal("10.500"),
                "unitCost", new BigDecimal("70.00"), "referenceNo", "PO-001")), 200);
        var stock = data(call("GET", "/inventory?page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(stock.path("total").asInt()).isEqualTo(1);
        assertThat(stock.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("10.500");
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "OUT", "quantity", new BigDecimal("2.500"),
                "unitCost", new BigDecimal("70.00"))), 200);
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "OUT", "quantity", new BigDecimal("99.000"),
                "unitCost", new BigDecimal("70.00"))), 409);
        assertThat(data(call("GET", "/inventory/changes?page=1&pageSize=10", a.token(), null, null), 200).path("total").asInt()).isEqualTo(2);
        assertThat(data(call("GET", "/inventory?page=1&pageSize=10", b.token(), null, null), 200).path("total").asInt()).isZero();
    }

    @Test
    void commissionSchemesPersistRulesAndRejectForeignItems() throws Exception {
        var rule = Map.of("itemId", productA, "minAmount", new BigDecimal("0.00"), "maxAmount", new BigDecimal("999.00"),
            "basis", "PERCENT", "rate", new BigDecimal("0.1000"), "fixedAmount", new BigDecimal("0.00"), "sortOrder", 0);
        var saved = data(call("POST", "/commissions", a.token(), null,
            Map.of("kind", "PRODUCT", "name", "产品基础提成", "basis", "PERCENT", "rate", new BigDecimal("0.1000"),
                "fixedAmount", new BigDecimal("0.00"), "description", "默认规则", "status", 1, "rules", List.of(rule))), 200);
        var schemes = data(call("GET", "/commissions?kind=PRODUCT&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(schemes.path("total").asInt()).isEqualTo(1);
        assertThat(schemes.path("records").get(0).path("rules").size()).isEqualTo(1);
        assertThat(data(call("GET", "/commissions?kind=PRODUCT&page=1&pageSize=10", b.token(), null, null), 200).path("total").asInt()).isZero();
        var foreign = Map.of("itemId", productA, "minAmount", new BigDecimal("0.00"), "basis", "PERCENT", "rate", new BigDecimal("0.1000"), "fixedAmount", new BigDecimal("0.00"), "sortOrder", 0);
        data(call("POST", "/commissions", b.token(), null,
            Map.of("kind", "PRODUCT", "name", "跨企业规则", "basis", "PERCENT", "rate", new BigDecimal("0.1000"),
                "fixedAmount", new BigDecimal("0.00"), "status", 1, "rules", List.of(foreign))), 400);
        assertThat(saved.asLong()).isPositive();
    }

    @Test
    void appointmentsPersistAcrossCalendarQueriesAndSupportTemporaryBlocks() throws Exception {
        var date = LocalDate.of(2026, 9, 28);
        var appointment = new java.util.LinkedHashMap<String, Object>();
        appointment.put("departmentId", storeA); appointment.put("appointmentDate", date.toString()); appointment.put("startTime", "10:30:00"); appointment.put("durationMinutes", 60);
        appointment.put("customerName", "林女士"); appointment.put("phone", "13800002018"); appointment.put("serviceName", "补水修护护理"); appointment.put("staffName", "小杨"); appointment.put("roomName", "护理间 A");
        appointment.put("status", "CONFIRMED"); appointment.put("color", "#1677ff"); appointment.put("note", "首次到店");
        var created = data(call("POST", "/appointments", a.token(), null,
            appointment), 200);
        var id = created.asLong();
        var rows = data(call("GET", "/appointments?date=" + date + "&departmentId=" + storeA + "&page=1&pageSize=100", a.token(), null, null), 200);
        assertThat(rows.path("total").asInt()).isEqualTo(1);
        assertThat(rows.path("records").get(0).path("customerName").asText()).isEqualTo("林女士");
        var options = data(call("GET", "/appointments/options?departmentId=" + storeA, a.token(), null, null), 200);
        assertThat(options.path("staff").isArray()).isTrue();
        assertThat(options.path("rooms").isArray()).isTrue();
        assertThat(options.path("services").isArray()).isTrue();
        data(call("PUT", "/appointments/" + id, a.token(), null,
            Map.of("departmentId", storeA, "appointmentDate", date.toString(), "startTime", "10:30:00", "durationMinutes", 60,
                "customerName", "临时占用", "serviceName", "时间占用", "staffName", "小杨", "roomName", "护理间 A",
                "status", "TEMP_BLOCK", "color", "#722ed1")), 200);
        assertThat(data(call("GET", "/appointments?date=" + date + "&status=TEMP_BLOCK&page=1&pageSize=100", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        data(call("DELETE", "/appointments/" + id, a.token(), null, null), 200);
        assertThat(data(call("GET", "/appointments?date=" + date + "&page=1&pageSize=100", b.token(), null, null), 200).path("total").asInt()).isZero();
    }

    @Test
    void inventoryBatchDocumentsAndCostAccountArePersistent() throws Exception {
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "IN", "quantity", new BigDecimal("10.000"),
                "unitCost", new BigDecimal("70.00"), "referenceNo", "PO-002")), 200);
        var batch = data(call("POST", "/inventory/batches", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "batchName", "2026春季批次", "productionDate", "2026-03-01",
                "expiryDate", "2028-03-01", "remark", "首批入库", "status", 1)), 200);
        assertThat(batch.asLong()).isPositive();
        assertThat(data(call("GET", "/inventory/batches?keyword=春季&page=1&pageSize=10", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        var doc = data(call("POST", "/inventory/documents", a.token(), null,
            Map.of("docType", "LIQUIDATION", "documentDate", "2026-09-28", "operatorName", "王店长", "status", "DRAFT", "remark", "月末盘点")), 200);
        assertThat(doc.asLong()).isPositive();
        assertThat(data(call("GET", "/inventory/documents?docType=LIQUIDATION&page=1&pageSize=10", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        var account = data(call("GET", "/inventory/account?departmentId=" + storeA + "&startDate=2026-01-01&endDate=2026-12-31&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(account.path("total").asInt()).isEqualTo(1);
        assertThat(account.path("records").get(0).path("endingQuantity").decimalValue()).isEqualByComparingTo("10.000");
        assertThat(account.path("records").get(0).path("inboundQuantity").decimalValue()).isEqualByComparingTo("10.000");
    }
}
