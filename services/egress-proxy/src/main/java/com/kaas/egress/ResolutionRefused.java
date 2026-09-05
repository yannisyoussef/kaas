package com.kaas.egress;

/** A resolution that produced nothing this proxy will connect to. */
public class ResolutionRefused extends RuntimeException {
    private final DenialReason reason;

    private final AddressClass addressClass;

    public ResolutionRefused(DenialReason reason, AddressClass addressClass, String message) {
        super(message);
        this.reason = reason;
        this.addressClass = addressClass;
    }

    public DenialReason reason() {
        return reason;
    }

    /** The class that caused the refusal, or null when the refusal was not about an address. */
    public AddressClass addressClass() {
        return addressClass;
    }
}
