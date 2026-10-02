package com.beauty.saas.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class AppointmentRequests {
    private AppointmentRequests() {}

    public record AppointmentSave(
        @NotNull @Positive Long departmentId,
        @NotNull LocalDate appointmentDate,
        @NotNull LocalTime startTime,
        @NotNull @Min(15) @Max(1440) Integer durationMinutes,
        @NotBlank @Size(max=80) String customerName,
        @Size(max=30) String phone,
        @Size(max=150) String serviceName,
        @Size(max=80) String staffName,
        @Size(max=80) String roomName,
        @NotBlank @Pattern(regexp="PENDING|CONFIRMED|ARRIVED|DONE|CANCELLED|TEMP_BLOCK") String status,
        @NotBlank @Pattern(regexp="#[0-9A-Fa-f]{6}") String color,
        @Size(max=500) String note,
        @Min(1) @Max(30) Integer partySize,
        Boolean needsTea, Boolean needsAirConditioner, Boolean bringsPet, Boolean bringsChild, Boolean needsBath,
        @Pattern(regexp="NORMAL|TEMP_BLOCK|RECURRING") String bookingType) {}

    public record RecurringAppointmentSave(@NotNull @Valid AppointmentSave appointment,
                                            @Min(2) @Max(52) int occurrences) {}

    public record AppointmentTimeSlot(@NotNull LocalTime start, @NotNull LocalTime end) {}

    public record AppointmentSettingsSave(
        @NotNull @Positive Long departmentId,
        @NotNull LocalTime businessStart, @NotNull LocalTime businessEnd,
        @NotNull List<@Positive Long> bookableStaffIds,
        @NotNull Boolean publicBookingEnabled,
        @Size(max=1500000) String publicImageData,
        @NotNull @Size(min=1, max=12) List<@Valid AppointmentTimeSlot> publicSlots,
        @NotNull @Size(min=1, max=7) List<@Min(1) @Max(7) Integer> publicWeekdays,
        @NotNull @Size(max=60) List<@NotNull LocalDate> closedDates,
        @NotNull @Min(0) @Max(43200) Integer advanceMinutes,
        @NotNull @Min(5) @Max(240) Integer intervalMinutes,
        @NotNull @Min(1) @Max(365) Integer maxAdvanceDays,
        @NotNull Boolean preventConflicts) {}

    public record AppointmentQuery(
        LocalDate date,
        LocalDate startDate,
        LocalDate endDate,
        Long departmentId,
        String status,
        String keyword,
        String searchBy,
        int page,
        int pageSize) {}
}
