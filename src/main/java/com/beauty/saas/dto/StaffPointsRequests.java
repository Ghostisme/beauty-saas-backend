package com.beauty.saas.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public final class StaffPointsRequests {
    private StaffPointsRequests() {}

    public record PointSave(
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 20) String pointType,
        @NotNull @Positive @Max(1_000_000_000L) Long points,
        @NotBlank @Size(max = 500) String reason,
        LocalDate effectiveDate
    ) {}
}
