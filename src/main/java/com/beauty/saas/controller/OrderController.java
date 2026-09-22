package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.OrderRequests.*;
import com.beauty.saas.order.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orders;
    @GetMapping("/options") public Result<Map<String,Object>> options() { return Result.success(orders.options()); }
    @GetMapping("/stores") public Result<Page<Map<String,Object>>> stores(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) { return Result.success(orders.stores(keyword,page,pageSize)); }
    @GetMapping public Result<Page<Map<String,Object>>> list(
        @RequestParam(defaultValue="all") String tab,@RequestParam(defaultValue="CUSTOMER") String searchType,@RequestParam(defaultValue="") String keyword,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required=false) Long storeId,@RequestParam(required=false) String consumptionType,@RequestParam(required=false) String paymentMethod,@RequestParam(required=false) String verification,
        @RequestParam(defaultValue="orderTime") String sortBy,@RequestParam(defaultValue="desc") String sortDirection,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {
        return Result.success(orders.list(new Query(tab,searchType,keyword,startDate,endDate,storeId,consumptionType,paymentMethod,verification,sortBy,sortDirection,page,pageSize)));
    }
    @GetMapping("/verification-settings") public Result<Map<String,Object>> settings() { return Result.success(orders.settings()); }
    @PutMapping("/verification-settings") public Result<Map<String,Object>> settings(@Valid @RequestBody VerificationSetting input) { return Result.success(orders.saveSettings(input)); }
    @GetMapping("/{id}") public Result<Map<String,Object>> detail(@PathVariable long id) { return Result.success(orders.detail(id)); }
    @PostMapping("/{id}/verification") public Result<Void> verify(@PathVariable long id,@Valid @RequestBody Verify input) { orders.verify(id,input); return Result.success(); }
}
