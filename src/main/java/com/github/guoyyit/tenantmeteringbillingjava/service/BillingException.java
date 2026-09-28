package com.github.guoyyit.tenantmeteringbillingjava.service;

/**
 * 业务异常，携带建议的 HTTP 状态码。
 */
public class BillingException extends RuntimeException {

    private final int httpStatus;

    public BillingException(String message) {
        this(message, 400);
    }

    public BillingException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
