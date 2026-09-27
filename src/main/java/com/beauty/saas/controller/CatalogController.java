package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.ItemRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.catalog.CatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CatalogController {
    private final CatalogService service;
    @GetMapping("/items") public Result<Page<Map<String,Object>>> items(@RequestParam String kind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Integer status){return Result.success(service.items(kind,page,pageSize,keyword,status));}
    @PostMapping("/items") public Result<Long> item(@RequestParam String kind,@Valid @RequestBody ItemSave input){return Result.success(service.saveItem(null,input,kind));}
    @PutMapping("/items/{id}") public Result<Long> item(@PathVariable long id,@RequestParam String kind,@Valid @RequestBody ItemSave input){return Result.success(service.saveItem(id,input,kind));}
    @DeleteMapping("/items/{id}") public Result<Void> item(@PathVariable long id,@RequestParam String kind){service.deleteItem(id,kind);return Result.success();}
    @GetMapping("/inventory") public Result<Page<Map<String,Object>>> inventory(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long itemId,@RequestParam(defaultValue="false") boolean shortageOnly){return Result.success(service.inventory(new InventoryQuery(page,pageSize,keyword,departmentId,itemId,shortageOnly)));}
    @PostMapping("/inventory/changes") public Result<Map<String,Object>> change(@Valid @RequestBody InventoryChange input){return Result.success(service.changeInventory(input));}
    @GetMapping("/inventory/changes") public Result<Page<Map<String,Object>>> changes(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long itemId){return Result.success(service.inventoryChanges(page,pageSize,departmentId,itemId));}
    @GetMapping("/commissions") public Result<Page<Map<String,Object>>> commissions(@RequestParam String kind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Integer status){return Result.success(service.commissions(kind,page,pageSize,keyword,status));}
    @PostMapping("/commissions") public Result<Long> commission(@Valid @RequestBody CommissionSave input){return Result.success(service.saveCommission(null,input));}
    @PutMapping("/commissions/{id}") public Result<Long> commission(@PathVariable long id,@Valid @RequestBody CommissionSave input){return Result.success(service.saveCommission(id,input));}
    @DeleteMapping("/commissions/{id}") public Result<Void> commission(@PathVariable long id,@RequestParam String kind){service.deleteCommission(id,kind);return Result.success();}
}
