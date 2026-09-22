package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.*;
import com.beauty.saas.iam.IdentityService;
import com.beauty.saas.iam.PlatformService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformController {
    private final IdentityService identity;
    private final PlatformService platform;
    @GetMapping("/summary") public Result<Map<String,Object>> summary() { return Result.success(platform.summary()); }
    @GetMapping("/tenants") public Result<Page<Map<String,Object>>> tenants(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Integer status) { return Result.success(platform.tenants(page,pageSize,keyword,status)); }
    @GetMapping("/tenants/{id}") public Result<Map<String,Object>> tenant(@PathVariable long id) { return Result.success(platform.tenant(id)); }
    @PutMapping("/tenants/{id}") public Result<Void> tenant(@PathVariable long id,@Valid @RequestBody EnterpriseSave input) { platform.save(id,input); return Result.success(); }
    @PostMapping("/tenants/{id}/admin") public Result<Long> admin(@PathVariable long id,@Valid @RequestBody EnterpriseAdminCreate input) { return Result.success(platform.createAdmin(id,input)); }
    @PostMapping("/tenants/{id}/admin/password") public Result<Void> password(@PathVariable long id,@Valid @RequestBody PasswordReset input) { platform.resetAdmin(id,input); return Result.success(); }
    @GetMapping("/data/{kind}") public Result<Page<Map<String,Object>>> data(@PathVariable String kind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long tenantId) { return Result.success(platform.data(kind,page,pageSize,keyword,tenantId)); }
    @PostMapping("/tenants")
    public Result<Map<String,Object>> provision(@RequestHeader(value="X-Platform-Key",required=false) String key, @Valid @RequestBody TenantCreate input) {
        return Result.success(identity.provision(key,input));
    }
}
