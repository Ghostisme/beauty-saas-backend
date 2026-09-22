package com.beauty.saas.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Result<Void>> business(ApiException e) { return ResponseEntity.status(e.status()).body(Result.error(e.status(), e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst().map(error -> error.getDefaultMessage()).orElse("请检查输入内容");
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Result<Void>> malformed(Exception e) { return ResponseEntity.badRequest().body(Result.error(400, "请求参数格式不正确")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Result<Void>> duplicate(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Result.error(409, "编码或账号已存在，请使用其他值")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception e) {
        log.error("Request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Result.error(500, "服务暂时不可用，请稍后重试"));
    }
}
