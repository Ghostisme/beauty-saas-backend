package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryWorkflowRequests {
    private InventoryWorkflowRequests() {}

    public record BatchSave(
        @NotNull @Positive Long departmentId, @NotNull @Positive Long itemId,
        @NotBlank @Size(max=120) String batchName,
        @DecimalMin(value="0.000") @Digits(integer=11, fraction=3) BigDecimal quantity,
        LocalDate productionDate, LocalDate expiryDate,
        @Size(max=300) String remark, @NotNull @Min(0) @Max(1) Integer status) {}
    public record BatchQuery(int page, int pageSize, String keyword, Long departmentId, Long itemId) {}

    public record DocumentSave(
        @NotBlank @Pattern(regexp="LIQUIDATION|TRANSFER_IN|TRANSFER_OUT|COST_ADJUST") String docType,
        @Size(max=80) String documentNo, Long sourceDepartmentId, Long targetDepartmentId,
        @NotNull LocalDate documentDate, @Size(max=80) String operatorName,
        @NotBlank @Pattern(regexp="DRAFT|PENDING|CONFIRMED|CANCELLED") String status,
        @Size(max=300) String remark) {}
    public record DocumentQuery(int page, int pageSize, String docType, String keyword, Long sourceDepartmentId, Long targetDepartmentId, String status, LocalDate startDate, LocalDate endDate) {}
    public record DocumentLineSave(
        @NotNull @Positive Long itemId,
        @NotNull @DecimalMin(value="0.001") @Digits(integer=11, fraction=3) BigDecimal quantity,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=12, fraction=2) BigDecimal unitCost,
        @Size(max=120) String batchName, LocalDate productionDate, LocalDate expiryDate,
        @Size(max=300) String remark) {}
    public record MovementDocumentSave(
        @NotBlank @Pattern(regexp="IN|OUT") String changeType,
        @NotNull @Positive Long departmentId,
        @Size(max=80) String documentNo,
        @NotNull LocalDate documentDate,
        @Size(max=80) String operatorName,
        @NotBlank @Pattern(regexp="DRAFT|CONFIRMED") String status,
        @Size(max=300) String remark,
        @NotEmpty @Size(max=100) List<@Valid DocumentLineSave> lines) {}
    public record AccountQuery(int page, int pageSize, String keyword, String brand, String category, Long departmentId, String startDate, String endDate) {}
}
