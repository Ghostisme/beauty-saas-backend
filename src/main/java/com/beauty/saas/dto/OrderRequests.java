package com.beauty.saas.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public final class OrderRequests {
    private OrderRequests() {}
    public record Query(String tab,String searchType,String keyword,LocalDate startDate,LocalDate endDate,
                        Long storeId,String consumptionType,String paymentMethod,String verification,
                        String sortBy,String sortDirection,int page,int pageSize) {}
    public record VerificationSetting(@NotNull Boolean enabled,@NotNull Boolean recheckAfterPerformanceChange,
                                      @NotNull @Min(0) Long version) {}
    public record Verify(@NotNull Boolean verified,@NotNull @Min(0) Long version) {}
}
