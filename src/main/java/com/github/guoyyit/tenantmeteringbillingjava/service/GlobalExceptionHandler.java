package com.github.guoyyit.tenantmeteringbillingjava.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handle(BillingException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(Map.of("error", ex.getMessage()));
    }
}
