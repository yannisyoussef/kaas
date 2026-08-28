package com.kaas.api.shared;

import java.io.IOException;

public final class RequestTooLargeException extends IOException {
    public RequestTooLargeException() {
        super("request body exceeds the configured limit");
    }
}
