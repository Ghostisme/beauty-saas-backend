package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.*;
import com.beauty.saas.dto.LoginResponse;
import com.beauty.saas.iam.*;
import com.beauty.saas.security.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/iam")
@RequiredArgsConstructor
public class IamController {
    private final IamService service;
    private final IdentityService identity;
    private final AccessService access;
    @GetMapping("/me") public Result<LoginResponse.UserInfo> me() { return Result.success(identity.profile(access.current())); }
    @PostMapping("/password") public Result<Void> password(@Valid @RequestBody PasswordChange input) { identity.changePassword(input); return Result.success(); }
    @GetMapping("/tenant") public Result<Map<String,Object>> tenant() { return Result.success(service.tenant()); }
    @PutMapping("/tenant") public Result<Void> tenant(@Valid @RequestBody TenantSave input) { service.saveTenant(input); return Result.success(); }
    @GetMapping("/options") public Result<Map<String,Object>> options() { return Result.success(service.options()); }
    @GetMapping("/departments") public Result<List<Map<String,Object>>> departments() { return Result.success(service.departments()); }
    @PostMapping("/departments") public Result<Long> department(@Valid @RequestBody DepartmentSave input) { return Result.success(service.saveDepartment(null,input)); }
    @PutMapping("/departments/{id}") public Result<Long> department(@PathVariable long id, @Valid @RequestBody DepartmentSave input) { return Result.success(service.saveDepartment(id,input)); }
    @DeleteMapping("/departments/{id}") public Result<Void> deleteDepartment(@PathVariable long id) { service.deleteDepartment(id); return Result.success(); }
    @GetMapping("/rooms") public Result<Page<Map<String,Object>>> rooms(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize, @RequestParam(defaultValue="") String keyword, @RequestParam(required=false) Long departmentId) { return Result.success(service.rooms(page,pageSize,keyword,departmentId)); }
    @PostMapping("/rooms") public Result<Long> room(@Valid @RequestBody RoomSave input) { return Result.success(service.saveRoom(null,input)); }
    @PutMapping("/rooms/{id}") public Result<Long> room(@PathVariable long id, @Valid @RequestBody RoomSave input) { return Result.success(service.saveRoom(id,input)); }
    @DeleteMapping("/rooms/{id}") public Result<Void> deleteRoom(@PathVariable long id) { service.deleteRoom(id); return Result.success(); }
    @GetMapping("/roles") public Result<Page<Map<String,Object>>> roles(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize, @RequestParam(defaultValue="") String keyword) { return Result.success(service.roles(page,pageSize,keyword)); }
    @PostMapping("/roles") public Result<Long> role(@Valid @RequestBody RoleSave input) { return Result.success(service.saveRole(null,input)); }
    @PutMapping("/roles/{id}") public Result<Long> role(@PathVariable long id, @Valid @RequestBody RoleSave input) { return Result.success(service.saveRole(id,input)); }
    @DeleteMapping("/roles/{id}") public Result<Void> deleteRole(@PathVariable long id) { service.deleteRole(id); return Result.success(); }
    @GetMapping("/users") public Result<Page<Map<String,Object>>> users(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize, @RequestParam(defaultValue="") String keyword, @RequestParam(required=false) Long departmentId, @RequestParam(required=false) Integer status) { return Result.success(service.users(page,pageSize,keyword,departmentId,status)); }
    @PostMapping("/users") public Result<Long> user(@Valid @RequestBody UserSave input) { return Result.success(service.saveUser(null,input)); }
    @PutMapping("/users/{id}") public Result<Long> user(@PathVariable long id, @Valid @RequestBody UserSave input) { return Result.success(service.saveUser(id,input)); }
    @DeleteMapping("/users/{id}") public Result<Void> deleteUser(@PathVariable long id) { service.deleteUser(id); return Result.success(); }
    @PostMapping("/users/{id}/password") public Result<Void> reset(@PathVariable long id, @Valid @RequestBody PasswordReset input) { service.resetPassword(id,input); return Result.success(); }
}
