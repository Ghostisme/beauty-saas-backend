package com.beauty.saas.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class CustomerRequests {
    private CustomerRequests() {}

    public record CustomerSave(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 30) String phone,
        @Size(max = 64) String code,
        @Size(max = 50) String level,
        @Size(max = 80) String source,
        LocalDate birthday,
        @Size(max = 1000) String remark,
        @Size(max = 80) String tracker,
        @Size(max = 80) String adviser,
        @Positive Long storeId,
        @Min(0) Integer cardCount,
        @DecimalMin(value = "0.00") BigDecimal balance,
        @DecimalMin(value = "0.00") BigDecimal spent,
        @Min(0) Integer visitCount,
        @Size(max = 255) String lastVisit) {}

    public record CustomerQuery(
        int page,
        int pageSize,
        String keyword,
        Long storeId,
        String source) {}

    public record StorageSave(
        @NotNull @Positive Long customerId,
        Long storeId,
        @NotBlank @Pattern(regexp = "PRODUCT|PROJECT") String storageType,
        @NotBlank @Size(max = 150) String itemName,
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Size(max = 300) String remark) {}

    public record StorageQuery(int page, int pageSize, String keyword, Long storeId, String storageType) {}
}
