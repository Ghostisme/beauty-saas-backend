package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public final class StaffAttendanceRequests {
    private StaffAttendanceRequests() {}

    public record Workday(
        @Min(1) @Max(7) int weekday,
        boolean enabled,
        @NotNull LocalTime start,
        @NotNull LocalTime end
    ) {}

    public record Location(
        @NotBlank @Size(max=100) String name,
        @Size(max=255) String address,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
    ) {}

    public record Wifi(
        @NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=64) String macAddress
    ) {}

    public record RuleSave(
        @NotBlank @Size(max=20) String name,
        @NotBlank String attendanceType,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> storeIds,
        boolean allStores,
        boolean allEmployees,
        @NotNull @Size(max=500) List<@NotNull @Positive Long> userIds,
        @NotNull @Size(min=7, max=7) List<@NotNull @Valid Workday> workdays,
        boolean overtimeEnabled,
        @NotBlank String overtimeMode,
        @Min(0) @Max(1440) Integer overtimeMinutes,
        LocalTime overtimeStartTime,
        boolean overtimeNonworkday,
        @NotBlank String punchMethod,
        @Min(1) @Max(10000) Integer radiusMeters,
        @NotNull @Size(max=100) List<@NotNull @Valid Location> locations,
        @NotNull @Size(max=100) List<@NotNull @Valid Wifi> wifis,
        boolean force
    ) {}
}
