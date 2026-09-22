package com.beauty.saas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class IamRequests {
    private IamRequests() {}
    public record TenantCreate(
        @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{2,39}", message="企业编码需为 3–40 位小写字母、数字或连字符") String code,
        @NotBlank @Size(max=100) String name, @NotBlank @Size(max=50) String adminName,
        @NotBlank @Size(min=8,max=72, message="管理员密码长度需为 8–72 位") String adminPassword, @Size(max=20) String phone,
        @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.-]{2,49}", message="管理员账号需为 3–50 位字母、数字、下划线、点或连字符") String adminUsername) {}
    public record EnterpriseAdminCreate(@NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.-]{2,49}") String username,
        @NotBlank @Size(max=50) String nickname, @NotBlank @Size(min=8,max=72) String password, @Size(max=20) String phone) {}
    public record EnterpriseSave(@NotBlank @Size(max=100) String name, @NotNull @Min(0) @Max(1) Integer status) {}
    public record TenantSave(@NotBlank @Size(max=100) String name) {}
    public record DepartmentSave(@Positive Long parentId, @NotBlank @Size(max=50) String code,
        @NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp="STORE|DEPARTMENT") String type,
        @NotNull @Min(0) @Max(99999) Integer sortOrder, @NotNull @Min(0) @Max(1) Integer status) {}
    public record RoomSave(@NotNull @Positive Long departmentId, @NotBlank @Size(max=50) String code,
        @NotBlank @Size(max=100) String name, @NotNull @Min(1) @Max(100) Integer capacity,
        @NotNull @Min(0) @Max(1) Integer status, @Size(max=500) String remark) {}
    public record RoleSave(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,49}", message="角色编码需为 2–50 位大写字母、数字或下划线") String code,
        @NotBlank @Size(max=50) String name, @NotNull @Min(0) @Max(1) Integer status,
        @Size(max=500) String description, @NotNull @Size(max=50) List<@NotBlank String> permissionCodes) {}
    public record RoleGrant(@NotNull @Positive Long roleId, @Positive Long departmentId) {}
    public record UserSave(@NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.-]{2,49}", message="账号需为 3–50 位字母、数字、下划线、点或连字符") String username,
        @NotBlank @Size(max=50) String nickname, @Size(max=20) String phone, @Email @Size(max=100) String email,
        @NotNull @Min(0) @Max(1) Integer status, @Size(max=72) String password,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> departmentIds,
        @NotNull @Size(min=1,max=100, message="至少分配一个角色") List<@NotNull @Valid RoleGrant> roleGrants) {}
    public record PasswordReset(@NotBlank @Size(min=8,max=72) String password) {}
    public record PasswordChange(@NotBlank @Size(max=128) String currentPassword, @NotBlank @Size(min=8,max=72) String newPassword) {}
    public record Page<T>(List<T> records, long total, int page, int pageSize) {}
}
