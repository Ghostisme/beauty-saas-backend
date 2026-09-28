package com.beauty.saas.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public final class AppointmentRequests {
    private AppointmentRequests() {}

    public record AppointmentSave(
        @NotNull @Positive Long departmentId,
        @NotNull LocalDate appointmentDate,
        @NotNull LocalTime startTime,
        @NotNull @Min(15) @Max(1440) Integer durationMinutes,
        @NotBlank @Size(max=80) String customerName,
        @Size(max=30) String phone,
        @NotBlank @Size(max=150) String serviceName,
        @Size(max=80) String staffName,
        @Size(max=80) String roomName,
        @NotBlank @Pattern(regexp="PENDING|CONFIRMED|ARRIVED|DONE|CANCELLED|TEMP_BLOCK") String status,
        @NotBlank @Pattern(regexp="#[0-9A-Fa-f]{6}") String color,
        @Size(max=500) String note) {}

    public record AppointmentQuery(
        LocalDate date,
        Long departmentId,
        String status,
        int page,
        int pageSize) {}
}
