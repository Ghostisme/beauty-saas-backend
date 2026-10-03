package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class StaffSopRequests {
    private StaffSopRequests() {}

    public record Subitem(
        @NotBlank @Size(max=40) String key,
        @NotBlank @Size(max=100) String name
    ) {}

    public record Item(
        @NotBlank @Size(max=40) String key,
        @NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="DAILY|WEEKLY|SEMIMONTHLY|MONTHLY") String frequency,
        @NotNull @Size(max=10) List<@NotNull @Valid Subitem> subitems
    ) {}

    public record RuleSave(
        @NotBlank @Size(max=100) String name,
        boolean allPositions,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> positionIds,
        @NotNull @Size(min=1,max=30) List<@NotNull @Valid Item> items
    ) {}

    public record CheckSave(
        @NotNull @Positive Long storeId,
        @NotNull @Positive Long userId,
        @NotNull @Positive Long ruleId,
        @NotBlank String leafKey,
        @NotNull LocalDate date,
        @NotNull Boolean checked,
        String mode,
        @Size(max=1000) String value
    ) {}
}
