package com.darshan.ledger.common.error;

// Machine-readable code returned in every error response. Each feature declares its own codes
// in an enum that implements this interface, so the catalogue grows next to the rules that use it.
public interface ErrorCode {

    String code();
}
