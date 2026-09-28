package com.github.guoyyit.tenantmeteringbillingjava.billing;

/** Thrown for invalid requests (bad tiers, closed periods, ...). */
public class ValidationException extends BillingException {

    public ValidationException(String message) {
        super(message);
    }
}
