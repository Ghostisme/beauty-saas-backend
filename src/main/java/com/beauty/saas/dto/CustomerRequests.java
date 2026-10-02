package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        @Size(max = 10) String gender,
        @Size(max = 10) String birthdayType,
        LocalDate joinDate,
        @Size(max = 4000000) String avatarUrl,
        @Size(max = 80) String referrer,
        @DecimalMin(value = "0.00") BigDecimal initialSpent,
        LocalDate referralDate,
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

    /** A line in a customer-storage batch.  itemId is optional so a historic
     * manually-entered storage record remains valid, while catalog-backed
     * records can retain their code/category for the detail screens. */
    public record StorageItemSave(
        @Positive Long itemId,
        @Size(max = 64) String itemCode,
        @Size(max = 150) String itemName,
        @Size(max = 100) String category,
        @Pattern(regexp = "PRODUCT|PROJECT") String storageType,
        @DecimalMin(value = "0.001") BigDecimal quantity) {}

    /** Supports both the original single-line API and the new batch form. */
    public record StorageSave(
        @NotNull @Positive Long customerId,
        Long storeId,
        @Pattern(regexp = "PRODUCT|PROJECT") String storageType,
        @Size(max = 150) String itemName,
        @DecimalMin(value = "0.001") BigDecimal quantity,
        @Size(max = 300) String remark,
        @Size(max = 100) List<@Valid StorageItemSave> items) {}

    public record StorageQuery(int page, int pageSize, String keyword, Long storeId, String storageType) {}

    public record StorageClaim(@NotNull @DecimalMin(value = "0.001") BigDecimal quantity) {}
}
