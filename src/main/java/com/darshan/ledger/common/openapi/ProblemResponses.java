package com.darshan.ledger.common.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

// Every error of this API is one shape: an RFC 9457 problem document with an errorCode and the
// correlationId of the request. It is described once, as reusable components, and attached to
// each operation according to what that operation can return, instead of being repeated on
// every controller method.
final class ProblemResponses {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String SCHEMA = "ProblemDetail";
    static final String BAD_REQUEST = "BadRequest";
    static final String INVALID_CREDENTIALS = "InvalidCredentials";
    static final String UNAUTHORIZED = "Unauthorized";
    static final String FORBIDDEN = "Forbidden";
    static final String NOT_FOUND = "NotFound";
    static final String CONFLICT = "Conflict";
    static final String UNPROCESSABLE_ENTITY = "UnprocessableEntity";
    static final String TOO_MANY_REQUESTS = "TooManyRequests";

    private static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA;
    private static final String RESPONSE_REF = "#/components/responses/";
    private static final String TRANSFERS_PATH = "/api/v1/transfers";

    private ProblemResponses() {
    }

    static void describe(OpenAPI api) {
        Components components = api.getComponents();
        components.addSchemas(SCHEMA, problemSchema());
        components.addResponses(BAD_REQUEST, response("The request is malformed or fails validation",
                "validationFailed", "A field failed validation", """
                {
                  "type": "about:blank",
                  "title": "Bad Request",
                  "status": 400,
                  "detail": "Request validation failed",
                  "instance": "/api/v1/transfers",
                  "errorCode": "VALIDATION_FAILED",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70",
                  "errors": [ { "field": "amount", "message": "must be greater than 0" } ]
                }
                """));
        components.addResponses(INVALID_CREDENTIALS, response("The username or password is wrong, or the login is disabled",
                "invalidCredentials", "Wrong username or password", """
                {
                  "type": "about:blank",
                  "title": "Unauthorized",
                  "status": 401,
                  "detail": "Invalid username or password",
                  "instance": "/api/v1/auth/token",
                  "errorCode": "INVALID_CREDENTIALS",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
                }
                """));
        components.addResponses(UNAUTHORIZED, response("The bearer token is missing, expired or invalid",
                "authenticationRequired", "No bearer token was sent", """
                {
                  "type": "about:blank",
                  "title": "Unauthorized",
                  "status": 401,
                  "detail": "Authentication is required",
                  "instance": "/api/v1/accounts/7/balance",
                  "errorCode": "AUTHENTICATION_REQUIRED",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
                }
                """));
        components.addResponses(FORBIDDEN, response("The token is valid but its roles or ownership do not allow this",
                "accessDenied", "A customer reading another customer's account", """
                {
                  "type": "about:blank",
                  "title": "Forbidden",
                  "status": 403,
                  "detail": "Access denied",
                  "instance": "/api/v1/accounts/9/balance",
                  "errorCode": "ACCESS_DENIED",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
                }
                """));
        components.addResponses(NOT_FOUND, response("No resource with that identifier", "resourceNotFound",
                "Unknown customer id", """
                {
                  "type": "about:blank",
                  "title": "Not Found",
                  "status": 404,
                  "detail": "Customer 42 not found",
                  "instance": "/api/v1/customers/42",
                  "errorCode": "RESOURCE_NOT_FOUND",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
                }
                """));
        components.addResponses(CONFLICT, response("The resource is in a state that does not allow the change, or a "
                + "concurrent request got there first; refetch and retry if appropriate", "transferNotPending",
                "Deciding a transfer that was already decided", """
                {
                  "type": "about:blank",
                  "title": "Conflict",
                  "status": 409,
                  "detail": "Transfer 12 is not pending approval",
                  "instance": "/api/v1/approvals/12/approve",
                  "errorCode": "TRANSFER_NOT_PENDING",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
                }
                """));
        components.addResponses(UNPROCESSABLE_ENTITY, response("A business rule refused the request; errorCode names "
                + "the rule", "insufficientFunds", "Available balance too low", """
                {
                  "type": "about:blank",
                  "title": "Unprocessable Entity",
                  "status": 422,
                  "detail": "Available balance 1000.00 is lower than 2500.00",
                  "instance": "/api/v1/transfers",
                  "errorCode": "INSUFFICIENT_FUNDS",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70",
                  "transferId": 31,
                  "transferReference": "TRF-0000000031"
                }
                """));
        components.addResponses(TOO_MANY_REQUESTS, response("The caller exceeded its transfer rate limit",
                "rateLimitExceeded", "More transfers per minute than allowed", """
                {
                  "type": "about:blank",
                  "title": "Too Many Requests",
                  "status": 429,
                  "detail": "Transfer rate limit exceeded",
                  "instance": "/api/v1/transfers",
                  "errorCode": "RATE_LIMIT_EXCEEDED",
                  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70",
                  "retryAfterSeconds": 3
                }
                """)
                .addHeaderObject("Retry-After", new Header()
                        .description("Whole seconds to wait before retrying")
                        .schema(new IntegerSchema())));
        api.getPaths().forEach((path, item) -> item.readOperationsMap()
                .forEach((method, operation) -> attach(path, method, operation)));
    }

    // The token endpoint is the only open operation; every other one is behind the bearer scheme
    // and the role or ownership rules, and every one that writes can hit a state or business rule.
    private static void attach(String path, PathItem.HttpMethod method, Operation operation) {
        ApiResponses responses = operation.getResponses();
        boolean open = operation.getSecurity() != null && operation.getSecurity().isEmpty();
        boolean writes = method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT;
        add(responses, "400", BAD_REQUEST);
        if (open) {
            add(responses, "401", INVALID_CREDENTIALS);
            return;
        }
        add(responses, "401", UNAUTHORIZED);
        add(responses, "403", FORBIDDEN);
        if (writes || path.contains("{")) {
            add(responses, "404", NOT_FOUND);
        }
        if (writes) {
            add(responses, "409", CONFLICT);
            add(responses, "422", UNPROCESSABLE_ENTITY);
        }
        if (writes && TRANSFERS_PATH.equals(path)) {
            add(responses, "429", TOO_MANY_REQUESTS);
        }
    }

    private static void add(ApiResponses responses, String status, String component) {
        responses.putIfAbsent(status, new ApiResponse().$ref(RESPONSE_REF + component));
    }

    private static ApiResponse response(String description, String exampleName, String summary, String json) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType()
                        .schema(new Schema<>().$ref(SCHEMA_REF))
                        .addExamples(exampleName, JsonExample.of(summary, json))));
    }

    private static Schema<?> problemSchema() {
        Schema<?> fieldViolation = new ObjectSchema()
                .addProperty("field", new StringSchema().example("amount"))
                .addProperty("message", new StringSchema().example("must be greater than 0"));
        return new ObjectSchema()
                .description("RFC 9457 problem document; every error of this API has this shape")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Unprocessable Entity"))
                .addProperty("status", new IntegerSchema().example(422))
                .addProperty("detail", new StringSchema().description("Human-readable explanation, safe to show"))
                .addProperty("instance", new StringSchema().example("/api/v1/transfers"))
                .addProperty("errorCode", new StringSchema()
                        .description("Machine-readable code, stable across releases")
                        .example("INSUFFICIENT_FUNDS"))
                .addProperty("correlationId", new StringSchema()
                        .description("Same value as the X-Correlation-Id response header"))
                .addProperty("errors", new ArraySchema()
                        .items(fieldViolation)
                        .description("Field violations, present on validation failures only"))
                .additionalProperties(Boolean.TRUE);
    }
}
