package com.github.guoyyit.tenantmeteringbillingjava.web;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingException;
import com.github.guoyyit.tenantmeteringbillingjava.billing.NotFoundException;
import com.github.guoyyit.tenantmeteringbillingjava.billing.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps service exceptions to stable JSON error responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ValidationException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException
                ? "malformed JSON request body"
                : e.getMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, Object>> billing(BillingException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
