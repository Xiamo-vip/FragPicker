package com.fragpicker.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> apiError(ApiException error) {
        return ResponseEntity.status(error.status()).body(ApiError.of(error.code(), error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidInput(MethodArgumentNotValidException error) {
        var fields = new LinkedHashMap<String, String>();
        for (var field : error.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(field.getField(), field.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "请检查输入内容", fields, Instant.now()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableJson() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("INVALID_JSON", "请求格式无效"));
    }
}
