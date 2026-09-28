package com.github.guoyyit.tenantmeteringbillingjava.billing;

/**
 * Base class for expected service errors mapped to HTTP 4xx responses.
 */
public class BillingException extends RuntimeException {

    public BillingException(String message) {
        super(message);
    }
}
