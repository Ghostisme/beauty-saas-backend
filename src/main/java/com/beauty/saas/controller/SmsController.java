package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.SmsRequests.*;
import com.beauty.saas.sms.SmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/sms")
@RequiredArgsConstructor
public class SmsController {
    private final SmsService sms;
    @GetMapping("/status") public Result<Map<String,Object>> status() { return Result.success(sms.status()); }
    @GetMapping("/settings") public Result<Map<String,Object>> settings() { return Result.success(sms.settings()); }
    @PutMapping("/settings/{code}") public Result<Map<String,Object>> settings(@PathVariable String code,@Valid @RequestBody SettingSave input) { return Result.success(sms.saveSetting(code,input)); }
    @GetMapping("/records") public Result<Page<Map<String,Object>>> records(
        @RequestParam(defaultValue="") String phone,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return Result.success(sms.records(new RecordQuery(phone,startDate,endDate,page,pageSize))); }
    @GetMapping("/records/export") public ResponseEntity<byte[]> export(
        @RequestParam(defaultValue="") String phone,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        byte[] file=sms.exportRecords(new RecordQuery(phone,startDate,endDate,1,100));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sms-records.csv\"")
            .cacheControl(CacheControl.noStore()).body(file);
    }
    @GetMapping("/billing") public Result<Map<String,Object>> billing() { return Result.success(sms.billing()); }
    @GetMapping("/recharges") public Result<Page<Map<String,Object>>> recharges(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return Result.success(sms.recharges(page,pageSize)); }
    @GetMapping("/recharges/{id}") public Result<Map<String,Object>> recharge(@PathVariable long id) { return Result.success(sms.recharge(id)); }
    @PostMapping("/recharges") public Result<Map<String,Object>> create(@Valid @RequestBody RechargeCreate input) { return Result.success(sms.createRecharge(input)); }
    @PostMapping("/recharges/{id}/cancel") public Result<Map<String,Object>> cancel(@PathVariable long id,@Valid @RequestBody RechargeCancel input) { return Result.success(sms.cancelRecharge(id,input)); }
}
