package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryCostRequests {
    private InventoryCostRequests() {}

    public record CostQuery(
        int page, int pageSize, String keyword, String brand, String category,
        Long departmentId, LocalDate startDate, LocalDate endDate) {}

    public record CostDetailQuery(
        long inventoryId, int page, int pageSize, LocalDate startDate, LocalDate endDate) {}

    public record CostAdjustmentLineSave(
        @NotNull @Positive Long itemId,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
        @Size(max = 15) String remark) {}

    public record CostAdjustmentSave(
        @NotNull @Positive Long departmentId,
        @Size(max = 80) String operatorName,
        @Size(max = 300) String remark,
        @NotEmpty @Size(max = 500) List<@Valid CostAdjustmentLineSave> lines) {}

    public record CostAdjustmentQuery(
        int page, int pageSize, String keyword, Long departmentId,
        LocalDate startDate, LocalDate endDate) {}
}
