package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class SmsRequests {
    private SmsRequests() {}
    public record ReminderConfig(
        @Min(1) @Max(4320) Integer leadMinutes,
        @Min(0) @Max(30) Integer advanceDays,
        @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]",message="发送时间格式应为 HH:mm") String sendTime,
        @Size(max=100) String visitNote, @Size(max=100) String benefitNote,
        Boolean customBenefitEnabled) {}
    public record SettingSave(@NotNull Boolean enabled, @NotNull @Min(0) Long version, @Valid ReminderConfig config) {}
    public record RecordQuery(String phone,LocalDate startDate,LocalDate endDate,int page,int pageSize) {}
    public record RechargeCreate(@NotNull @Positive Long packageId, @NotNull @Min(1) Long packageVersion,
        @NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",message="请重新打开充值窗口后提交") String idempotencyKey) {}
    public record RechargeCancel(@NotNull @Min(0) Long version) {}
}
