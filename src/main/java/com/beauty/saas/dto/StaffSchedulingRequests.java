package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class StaffSchedulingRequests {
    private StaffSchedulingRequests() {}

    public record Period(@NotNull LocalTime start, @NotNull LocalTime end) {}
    public record ShiftSave(
        @NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="#[0-9a-fA-F]{6}") String color,
        @NotNull @Size(min=1,max=100) List<@NotNull @Positive Long> storeIds,
        @NotNull @Size(min=1,max=8) List<@NotNull @Valid Period> periods
    ) {}
    public record ParticipantsSave(
        @NotNull @Positive Long storeId,
        @NotNull @Size(max=500) List<@NotNull @Positive Long> userIds
    ) {}
    public record AssignmentSave(
        @NotNull @Positive Long storeId,
        @NotNull @Positive Long userId,
        @NotNull LocalDate date,
        @Positive Long shiftId
    ) {}
    public record CopyWeek(@NotNull @Positive Long storeId, @NotNull LocalDate startDate) {}
}
