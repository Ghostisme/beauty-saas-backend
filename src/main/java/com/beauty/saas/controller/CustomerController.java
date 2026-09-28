package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.customer.CustomerService;
import com.beauty.saas.dto.CustomerRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customers;

    @GetMapping public Result<Page<Map<String,Object>>> list(
        @RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,
        @RequestParam(required=false) Long storeId,
        @RequestParam(required=false) String source) {
        return Result.success(customers.list(new CustomerQuery(page, pageSize, keyword, storeId, source)));
    }

    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable long id) { return Result.success(customers.detail(id)); }
    @GetMapping("/storage") public Result<Page<Map<String,Object>>> storage(
        @RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,
        @RequestParam(required=false) Long storeId,
        @RequestParam(required=false) String storageType) {
        return Result.success(customers.storages(new StorageQuery(page, pageSize, keyword, storeId, storageType)));
    }
    @PostMapping public Result<Long> create(@Valid @RequestBody CustomerSave input) { return Result.success(customers.save(null, input)); }
    @PutMapping("/{id}") public Result<Long> update(@PathVariable long id, @Valid @RequestBody CustomerSave input) { return Result.success(customers.save(id, input)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable long id) { customers.delete(id); return Result.success(); }
    @PostMapping("/storage") public Result<Long> createStorage(@Valid @RequestBody StorageSave input) { return Result.success(customers.saveStorage(input)); }
}
