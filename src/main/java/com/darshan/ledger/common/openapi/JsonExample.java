package com.darshan.ledger.common.openapi;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.examples.Example;
import java.io.IOException;
import java.io.UncheckedIOException;

// Examples are written as JSON text because that is what a reader compares a response against;
// swagger-core must receive them parsed, or it would render the text as one quoted string.
final class JsonExample {

    private JsonExample() {
    }

    static Example of(String summary, String json) {
        try {
            return new Example().summary(summary).value(Json.mapper().readTree(json));
        } catch (IOException ex) {
            throw new UncheckedIOException("Example is not valid JSON: " + summary, ex);
        }
    }
}
