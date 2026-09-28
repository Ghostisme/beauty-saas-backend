package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.InventoryWorkflowRequests.*;
import com.beauty.saas.inventory.InventoryWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryWorkflowController {
    private final InventoryWorkflowService service;
    @GetMapping("/batches") public Result<Page<Map<String,Object>>> batches(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long itemId) { return Result.success(service.batches(new BatchQuery(page,pageSize,keyword,departmentId,itemId))); }
    @PostMapping("/batches") public Result<Long> batch(@Valid @RequestBody BatchSave input) { return Result.success(service.saveBatch(input)); }
    @DeleteMapping("/batches/{id}") public Result<Void> batch(@PathVariable long id) { service.deleteBatch(id); return Result.success(); }
    @GetMapping("/documents") public Result<Page<Map<String,Object>>> documents(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String docType,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long sourceDepartmentId,@RequestParam(required=false) Long targetDepartmentId,@RequestParam(required=false) String status) { return Result.success(service.documents(new DocumentQuery(page,pageSize,docType,keyword,sourceDepartmentId,targetDepartmentId,status))); }
    @PostMapping("/documents") public Result<Long> document(@Valid @RequestBody DocumentSave input) { return Result.success(service.saveDocument(input)); }
    @GetMapping("/account") public Result<Page<Map<String,Object>>> account(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String startDate,@RequestParam(required=false) String endDate) { return Result.success(service.account(new AccountQuery(page,pageSize,keyword,departmentId,startDate,endDate))); }
}
