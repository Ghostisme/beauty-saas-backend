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

    @Test
    void costAccountingSupportsWeightedAverageDetailsAndAdjustments() throws Exception {
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "IN", "quantity", new BigDecimal("10.000"),
                "unitCost", new BigDecimal("70.00"), "referenceNo", "COST-PO-001")), 200);
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "OUT", "quantity", new BigDecimal("2.000"),
                "unitCost", new BigDecimal("70.00"), "referenceNo", "COST-OUT-001")), 200);

        var accounting = data(call("GET", "/inventory/cost-accounting?departmentId=" + storeA
            + "&startDate=2026-01-01&endDate=2026-12-31&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(accounting.path("total").asInt()).isEqualTo(1);
        var row = accounting.path("records").get(0);
        assertThat(row.path("openingQuantity").decimalValue()).isEqualByComparingTo("0.000");
        assertThat(row.path("inboundQuantity").decimalValue()).isEqualByComparingTo("10.000");
        assertThat(row.path("outboundQuantity").decimalValue()).isEqualByComparingTo("2.000");
        assertThat(row.path("endingQuantity").decimalValue()).isEqualByComparingTo("8.000");
        assertThat(row.path("endingUnitCost").decimalValue()).isEqualByComparingTo("70.00");

        var inventoryId = row.path("id").asLong();
        var details = data(call("GET", "/inventory/cost-accounting/" + inventoryId
            + "/details?startDate=2026-01-01&endDate=2026-12-31&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(details.path("total").asInt()).isEqualTo(2);
        assertThat(details.path("records").findValuesAsText("type")).contains("入库", "出库");

        data(call("POST", "/inventory/cost-adjustments", a.token(), null,
            Map.of("departmentId", storeA, "operatorName", "负责人", "remark", "月末成本复核",
                "lines", List.of(Map.of("itemId", productA, "unitCost", new BigDecimal("80.00"), "remark", "复核")))), 200);
        var adjusted = data(call("GET", "/inventory/cost-accounting?departmentId=" + storeA
            + "&startDate=2026-01-01&endDate=2026-12-31&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(adjusted.path("records").get(0).path("endingUnitCost").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(data(call("GET", "/inventory/cost-adjustments?departmentId=" + storeA + "&page=1&pageSize=10", a.token(), null, null), 200)
            .path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET", "/inventory/cost-adjustments?keyword=护理精华&page=1&pageSize=10", a.token(), null, null), 200)
            .path("total").asInt()).isEqualTo(1);

        var secondProduct = data(call("POST", "/items?kind=PRODUCT", a.token(), null,
            Map.of("code", "SKU-COST-2", "name", "核算面膜", "category", "护理", "price", new BigDecimal("60.00"),
                "unit", "盒", "status", 1)), 200).asLong();
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", secondProduct, "changeType", "IN", "quantity", new BigDecimal("2.000"),
                "unitCost", new BigDecimal("30.00"), "referenceNo", "COST-PO-002")), 200);
        var paged = data(call("GET", "/inventory/cost-accounting?departmentId=" + storeA
            + "&startDate=2026-01-01&endDate=2026-12-31&page=1&pageSize=1", a.token(), null, null), 200);
        assertThat(paged.path("total").asInt()).isEqualTo(2);
        assertThat(paged.path("records").size()).isEqualTo(1);
        assertThat(paged.path("summary").path("endingCost").decimalValue()).isEqualByComparingTo("700.00");
    }

    @Test
    void transferInboundCreatesPendingDocumentWithLinesAndSupportsDetailAndRevoke() throws Exception {
        var targetStore = data(call("POST", "/iam/departments", root, a.id(),
            Map.of("name", "调入门店", "code", "TRANSFER_TARGET", "type", "STORE", "sortOrder", 1, "status", 1)), 200).asLong();
        var transfer = Map.of(
            "docType", "TRANSFER_IN", "sourceDepartmentId", storeA, "targetDepartmentId", targetStore,
            "documentDate", LocalDate.now().toString(), "operatorName", "负责人", "status", "PENDING",
            "lines", List.of(Map.of("itemId", productA, "quantity", new BigDecimal("1.000"), "unitCost", new BigDecimal("1314.00"))));
        var documentId = data(call("POST", "/inventory/transfers", a.token(), null, transfer), 200).asLong();
        assertThat(documentId).isPositive();
        var documents = data(call("GET", "/inventory/documents?docType=TRANSFER_IN&status=PENDING&sourceDepartmentId="
            + storeA + "&targetDepartmentId=" + targetStore + "&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(documents.path("total").asInt()).isEqualTo(1);
        assertThat(documents.path("records").get(0).path("documentNo").asText()).startsWith("DB-");
        var detail = data(call("GET", "/inventory/documents/" + documentId, a.token(), null, null), 200);
        assertThat(detail.path("status").asText()).isEqualTo("PENDING");
        assertThat(detail.path("lines").size()).isEqualTo(1);
        assertThat(detail.path("lines").get(0).path("unitCost").decimalValue()).isEqualByComparingTo("1314.00");
        assertThat(data(call("GET", "/inventory?itemId=" + productA + "&departmentId=" + storeA,
            a.token(), null, null), 200).path("total").asInt()).isZero();
        data(call("GET", "/inventory/documents/" + documentId, b.token(), null, null), 404);
        data(call("POST", "/inventory/documents/" + documentId + "/revoke", a.token(), null, null), 200);
        var cancelled = data(call("GET", "/inventory/documents/" + documentId, a.token(), null, null), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void transferInboundCanBeAcceptedAndMovesStockBetweenStores() throws Exception {
        var targetStore = data(call("POST", "/iam/departments", root, a.id(),
            Map.of("name", "收货门店", "code", "TRANSFER_RECEIVE", "type", "STORE", "sortOrder", 1, "status", 1)), 200).asLong();
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "IN", "quantity", new BigDecimal("5.000"),
                "unitCost", new BigDecimal("1314.00"), "referenceNo", "TRANSFER-SEED")), 200);
        var transfer = Map.of(
            "docType", "TRANSFER_IN", "sourceDepartmentId", storeA, "targetDepartmentId", targetStore,
            "documentDate", LocalDate.now().toString(), "operatorName", "负责人", "status", "PENDING",
            "lines", List.of(Map.of("itemId", productA, "quantity", new BigDecimal("2.000"), "unitCost", new BigDecimal("1314.00"))));
        var documentId = data(call("POST", "/inventory/transfers", a.token(), null, transfer), 200).asLong();

        data(call("POST", "/inventory/documents/" + documentId + "/accept", a.token(), null, null), 200);
        assertThat(data(call("GET", "/inventory/documents/" + documentId, a.token(), null, null), 200).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data(call("GET", "/inventory?itemId=" + productA + "&departmentId=" + storeA, a.token(), null, null), 200)
            .path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("3.000");
        assertThat(data(call("GET", "/inventory?itemId=" + productA + "&departmentId=" + targetStore, a.token(), null, null), 200)
            .path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.000");
    }

    @Test
    void movementDocumentsApplyMultipleLinesAndExposeStockDetails() throws Exception {
        var secondProduct = data(call("POST", "/items?kind=PRODUCT", a.token(), null,
            Map.of("code", "SKU-002", "name", "护理面膜", "category", "护理", "price", new BigDecimal("50.00"),
                "unit", "盒", "status", 1)), 200).asLong();
        var line = new java.util.LinkedHashMap<String, Object>();
        line.put("itemId", productA); line.put("quantity", new BigDecimal("3.500")); line.put("unitCost", new BigDecimal("72.00"));
        line.put("batchName", "入库批次-001"); line.put("productionDate", "2026-09-01"); line.put("expiryDate", "2028-09-01");
        var secondLine = new java.util.LinkedHashMap<String, Object>();
        secondLine.put("itemId", secondProduct); secondLine.put("quantity", new BigDecimal("2.000")); secondLine.put("unitCost", new BigDecimal("25.00"));
        var movement = new java.util.LinkedHashMap<String, Object>();
        movement.put("changeType", "IN"); movement.put("departmentId", storeA); movement.put("documentDate", "2026-10-01");
        movement.put("operatorName", "负责人"); movement.put("status", "CONFIRMED"); movement.put("remark", "产品入库单"); movement.put("lines", List.of(line, secondLine));
        var documentId = data(call("POST", "/inventory/documents/with-lines", a.token(), null, movement), 200).asLong();
        assertThat(documentId).isPositive();

        var stock = data(call("GET", "/inventory?page=1&pageSize=10&itemId=" + productA, a.token(), null, null), 200);
        var inventoryId = stock.path("records").get(0).path("id").asLong();
        assertThat(stock.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("3.500");
        var secondStock = data(call("GET", "/inventory?page=1&pageSize=10&itemId=" + secondProduct, a.token(), null, null), 200);
        assertThat(secondStock.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.000");
        assertThat(data(call("GET", "/inventory/" + inventoryId + "/changes?page=1&pageSize=50", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET", "/inventory/" + inventoryId + "/changes?page=1&pageSize=50&startDate=2025-01-01&endDate=2025-12-31", a.token(), null, null), 200).path("total").asInt()).isEqualTo(0);
        assertThat(data(call("GET", "/inventory/" + inventoryId + "/changes?page=1&pageSize=50&startDate=2026-10-01&endDate=2026-10-01", a.token(), null, null), 200).path("total").asInt()).isEqualTo(1);
        var batches = data(call("GET", "/inventory/batches?departmentId=" + storeA + "&itemId=" + productA + "&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(batches.path("total").asInt()).isEqualTo(1);
        assertThat(batches.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("3.500");

        var outboundLine = new java.util.LinkedHashMap<String, Object>();
        outboundLine.put("itemId", productA); outboundLine.put("quantity", new BigDecimal("1.500")); outboundLine.put("unitCost", new BigDecimal("72.00")); outboundLine.put("batchName", "入库批次-001");
        var outbound = new java.util.LinkedHashMap<String, Object>();
        outbound.put("changeType", "OUT"); outbound.put("departmentId", storeA); outbound.put("documentDate", "2026-10-01");
        outbound.put("operatorName", "负责人"); outbound.put("status", "CONFIRMED"); outbound.put("lines", List.of(outboundLine));
        data(call("POST", "/inventory/documents/with-lines", a.token(), null, outbound), 200);
        stock = data(call("GET", "/inventory?page=1&pageSize=10&itemId=" + productA, a.token(), null, null), 200);
        assertThat(stock.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.000");
        batches = data(call("GET", "/inventory/batches?departmentId=" + storeA + "&itemId=" + productA + "&page=1&pageSize=10", a.token(), null, null), 200);
        assertThat(batches.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.000");
    }

    @Test
    void liquidationDocumentsSnapshotCountsAndCanSynchronizeInventory() throws Exception {
        data(call("POST", "/inventory/changes", a.token(), null,
            Map.of("departmentId", storeA, "itemId", productA, "changeType", "IN", "quantity", new BigDecimal("5.000"),
                "unitCost", new BigDecimal("70.00"), "referenceNo", "PO-STOCKTAKE")), 200);

        var draftLine = Map.of("itemId", productA, "actualQuantity", BigDecimal.ZERO, "remark", "待复核");
        var draft = new java.util.LinkedHashMap<String, Object>();
        draft.put("departmentId", storeA); draft.put("documentDate", "2026-10-01"); draft.put("operatorName", "负责人");
        draft.put("status", "DRAFT"); draft.put("syncInventory", true); draft.put("lines", List.of(draftLine));
        assertThat(data(call("POST", "/inventory/liquidations", a.token(), null, draft), 200).asLong()).isPositive();
        assertThat(data(call("GET", "/inventory?page=1&pageSize=10&itemId=" + productA, a.token(), null, null), 200)
            .path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("5.000");

        var confirmedLine = Map.of("itemId", productA, "actualQuantity", new BigDecimal("2.500"), "remark", "复核后调整");
        var confirmed = new java.util.LinkedHashMap<String, Object>();
        confirmed.put("departmentId", storeA); confirmed.put("documentDate", "2026-10-01"); confirmed.put("operatorName", "负责人");
        confirmed.put("status", "CONFIRMED"); confirmed.put("syncInventory", true); confirmed.put("lines", List.of(confirmedLine));
        assertThat(data(call("POST", "/inventory/liquidations", a.token(), null, confirmed), 200).asLong()).isPositive();
        var stock = data(call("GET", "/inventory?page=1&pageSize=10&itemId=" + productA, a.token(), null, null), 200);
        var inventoryId = stock.path("records").get(0).path("id").asLong();
        assertThat(stock.path("records").get(0).path("quantity").decimalValue()).isEqualByComparingTo("2.500");
        var changes = data(call("GET", "/inventory/" + inventoryId + "/changes?page=1&pageSize=50", a.token(), null, null), 200);
        assertThat(changes.path("records").findValuesAsText("changeType")).contains("ADJUST");
    }

    @Test
    void inventorySettingsAreTenantScopedVersionedAndPersisted() throws Exception {
        var defaults = data(call("GET", "/inventory/settings", a.token(), null, null), 200);
        assertThat(defaults.path("preventOrderOnShortage").asBoolean()).isFalse();
        assertThat(defaults.path("expiryAlertMonths").asInt()).isEqualTo(6);
        var saved = data(call("PUT", "/inventory/settings", a.token(), null,
            Map.of("preventOrderOnShortage", true, "transferAutoConfirmEnabled", true, "transferAutoConfirmDays", 3,
                "stockAlertEnabled", true, "stockAlertValue", new BigDecimal("2.000"), "expiryAlertEnabled", true,
                "expiryAlertMonths", 12, "salesDeductInventory", false, "deleteProductSyncInventory", true, "version", 0)), 200);
        assertThat(saved.path("preventOrderOnShortage").asBoolean()).isTrue();
        assertThat(saved.path("stockAlertValue").decimalValue()).isEqualByComparingTo("2.000");
        assertThat(saved.path("version").asLong()).isEqualTo(0);
        var stored = data(call("GET", "/inventory/settings", a.token(), null, null), 200);
        assertThat(stored.path("salesDeductInventory").asBoolean()).isFalse();
        var updated = data(call("PUT", "/inventory/settings", a.token(), null,
            Map.of("preventOrderOnShortage", false, "transferAutoConfirmEnabled", false, "transferAutoConfirmDays", 0,
                "stockAlertEnabled", false, "stockAlertValue", BigDecimal.ZERO, "expiryAlertEnabled", false,
                "expiryAlertMonths", 6, "salesDeductInventory", true, "deleteProductSyncInventory", true, "version", 0)), 200);
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        data(call("PUT", "/inventory/settings", a.token(), null,
            Map.of("preventOrderOnShortage", true, "transferAutoConfirmEnabled", true, "transferAutoConfirmDays", 5,
                "stockAlertEnabled", true, "stockAlertValue", new BigDecimal("3.000"), "expiryAlertEnabled", true,
                "expiryAlertMonths", 12, "salesDeductInventory", false, "deleteProductSyncInventory", true, "version", 0)), 409);
        assertThat(data(call("GET", "/inventory/settings", b.token(), null, null), 200).path("version").asLong()).isEqualTo(0);
    }
}
