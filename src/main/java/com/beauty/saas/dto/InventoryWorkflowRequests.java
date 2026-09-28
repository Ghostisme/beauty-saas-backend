package com.beauty.saas.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class InventoryWorkflowRequests {
    private InventoryWorkflowRequests() {}

    public record BatchSave(
        @NotNull @Positive Long departmentId, @NotNull @Positive Long itemId,
        @NotBlank @Size(max=120) String batchName, LocalDate productionDate, LocalDate expiryDate,
        @Size(max=300) String remark, @NotNull @Min(0) @Max(1) Integer status) {}
    public record BatchQuery(int page, int pageSize, String keyword, Long departmentId, Long itemId) {}

    public record DocumentSave(
        @NotBlank @Pattern(regexp="LIQUIDATION|TRANSFER_IN|TRANSFER_OUT|COST_ADJUST") String docType,
        @Size(max=80) String documentNo, Long sourceDepartmentId, Long targetDepartmentId,
        @NotNull LocalDate documentDate, @Size(max=80) String operatorName,
        @NotBlank @Pattern(regexp="DRAFT|PENDING|CONFIRMED|CANCELLED") String status,
        @Size(max=300) String remark) {}
    public record DocumentQuery(int page, int pageSize, String docType, String keyword, Long sourceDepartmentId, Long targetDepartmentId, String status, LocalDate startDate, LocalDate endDate) {}
    public record AccountQuery(int page, int pageSize, String keyword, String brand, String category, Long departmentId, String startDate, String endDate) {}
}
