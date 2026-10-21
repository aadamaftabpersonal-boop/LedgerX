package com.ledgerx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OpenApiIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;

    @Test
    void specIsServedWithoutAnApiKeyAndDescribesTheRealEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("LedgerX API"))
                .andExpect(jsonPath("$.paths['/transfers'].post").exists())
                .andExpect(jsonPath("$.paths['/transfers/{id}/reverse'].post").exists())
                .andExpect(jsonPath("$.paths['/wallets/{id}/deposit'].post").exists())
                .andExpect(jsonPath("$.paths['/keys/rotate'].post").exists())
                .andExpect(jsonPath("$.paths['/admin/reconciliation'].get").exists());
    }

    @Test
    void bothAuthenticationSchemesAreDocumented() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.adminKey.name").value("X-Admin-Key"));
    }

    @Test
    void swaggerUiIsReachableWithoutAnApiKey() throws Exception {
        // /swagger-ui.html redirects to the bundled UI; neither step may demand an X-API-Key.
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
