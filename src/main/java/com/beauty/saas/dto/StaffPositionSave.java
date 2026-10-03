package com.beauty.saas.dto;

import jakarta.validation.constraints.*;

public record StaffPositionSave(
    @NotBlank @Size(max=100) String name,
    @Size(max=50) String code,
    @Size(max=500) String remark,
    @NotNull @Min(0) @Max(1) Integer status
) {}
