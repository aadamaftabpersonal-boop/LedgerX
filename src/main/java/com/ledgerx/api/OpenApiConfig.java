package com.ledgerx.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Interactive docs at /swagger-ui.html, machine-readable spec at /v3/api-docs. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "LedgerX API",
                version = "1.0",
                description = "Transaction-safe wallet and payments engine. Amounts are integer minor units "
                        + "(paise, cents). Transfers and reversals require an Idempotency-Key header and are safe to retry."),
        security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key", description = "Client API key, created via POST /admin/clients")
@SecurityScheme(name = "adminKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Admin-Key", description = "Operator key (LEDGERX_ADMIN_KEY)")
public class OpenApiConfig {}
