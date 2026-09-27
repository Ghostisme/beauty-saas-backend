package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class ItemRequests {
    private ItemRequests() {}
    public record ItemSave(
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{1,63}", message="品项编码需为 2–64 位字母、数字、下划线或连字符") String code,
        @NotBlank @Size(max=150) String name, @Size(max=100) String category,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal price,
        @Min(1) @Max(1440) Integer durationMinutes, @Size(max=30) String unit,
        @Size(max=80) String spec, @Size(max=1000) String description, @NotNull @Min(0) @Max(1) Integer status) {}

    public record InventoryQuery(int page, int pageSize, String keyword, Long departmentId, Long itemId, boolean shortageOnly) {}
    public record InventoryChange(@NotNull @Positive Long departmentId, @NotNull @Positive Long itemId,
        @NotBlank @Pattern(regexp="IN|OUT|ADJUST") String changeType,
        @NotNull @DecimalMin(value="0.001") @Digits(integer=11,fraction=3) BigDecimal quantity,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal unitCost,
        @DecimalMin(value="0.00") @Digits(integer=11,fraction=3) BigDecimal warningValue,
        @Size(max=300) String reason, @Size(max=80) String referenceNo) {}

    public record CommissionRule(@Positive Long itemId, @NotNull @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal minAmount,
        @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal maxAmount,
        @NotBlank @Pattern(regexp="PERCENT|AMOUNT") String basis,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=4,fraction=4) BigDecimal rate,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal fixedAmount,
        @NotNull @Min(0) Integer sortOrder) {}
    public record CommissionSave(@NotBlank @Pattern(regexp="PROJECT|PRODUCT|CARD|STEP") String kind,
        @NotBlank @Size(max=120) String name, @NotBlank @Pattern(regexp="PERCENT|AMOUNT") String basis,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=4,fraction=4) BigDecimal rate,
        @NotNull @DecimalMin(value="0.00") @Digits(integer=12,fraction=2) BigDecimal fixedAmount,
        @Size(max=500) String description, @NotNull @Min(0) @Max(1) Integer status,
        @NotNull @Size(max=100) List<@Valid CommissionRule> rules) {}
    public record Page<T>(List<T> records, long total, int page, int pageSize) {}
}
