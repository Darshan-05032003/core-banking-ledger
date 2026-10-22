package com.darshan.ledger.notification;

// A record this consumer can never process, however often it is retried: not JSON, a schema
// version it does not know, or an account that does not exist here. It goes straight to the
// dead-letter topic.
public class UnsupportedEventException extends RuntimeException {

    public UnsupportedEventException(String message) {
        super(message);
    }

    public UnsupportedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
