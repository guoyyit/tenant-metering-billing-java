package com.github.guoyyit.tenantmeteringbillingjava.billing;

/** Thrown when a referenced tenant or period does not exist. */
public class NotFoundException extends BillingException {

    public NotFoundException(String message) {
        super(message);
    }
}
