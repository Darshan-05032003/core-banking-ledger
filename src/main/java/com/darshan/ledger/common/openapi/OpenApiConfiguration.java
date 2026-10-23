package com.darshan.ledger.common.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// What springdoc cannot read from the controllers: the document's identity, the bearer scheme
// that applies to every operation unless one opts out, the tag order, the shared error contract
// and the worked examples of the main flows.
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI ledgerOpenApi(BuildProperties build) {
        return new OpenAPI()
                .info(new Info()
                        .title("Core Banking Ledger API")
                        .version(build.getVersion())
                        .description("Customers, accounts, cash operations and transfers recorded in a "
                                + "double-entry ledger. Obtain a token from POST /api/v1/auth/token and send it as "
                                + "a bearer token; every error is an RFC 9457 problem document with an errorCode "
                                + "and the correlationId of the request."))
                .tags(ApiTags.ordered())
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by POST /api/v1/auth/token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    OpenApiCustomizer problemResponsesCustomizer() {
        return ProblemResponses::describe;
    }

    @Bean
    OpenApiCustomizer flowExamplesCustomizer() {
        return FlowExamples::attach;
    }
}
