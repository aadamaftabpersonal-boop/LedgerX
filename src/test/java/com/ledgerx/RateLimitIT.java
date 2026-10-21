package com.ledgerx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ledgerx.rate-limit.enabled=true",
        "ledgerx.rate-limit.requests-per-second=1",
        "ledgerx.rate-limit.burst=3"})
class RateLimitIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String newClientKey() throws Exception {
        String body = mvc.perform(post("/admin/clients").header("X-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"rl-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("apiKey").asText();
    }

    private void call(String key, int expectedStatus) throws Exception {
        mvc.perform(get("/wallets/" + UUID.randomUUID()).header("X-API-Key", key))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void requestsBeyondTheBurstGet429WithRetryAfterAndOtherClientsAreUnaffected() throws Exception {
        String key = newClientKey();
        String otherKey = newClientKey();

        call(key, 404);
        call(key, 404);
        call(key, 404);
        mvc.perform(get("/wallets/" + UUID.randomUUID()).header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        call(otherKey, 404);
    }

    @Test
    void tokensRefillSoTheClientCanContinueAfterWaiting() throws Exception {
        String key = newClientKey();
        call(key, 404);
        call(key, 404);
        call(key, 404);
        call(key, 429);

        Thread.sleep(1_200); // one token per second

        call(key, 404);
    }

    @Test
    void healthChecksAndAdminEndpointsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeTheyCanConsumeAnyonesBudget() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/wallets/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
    }
}
