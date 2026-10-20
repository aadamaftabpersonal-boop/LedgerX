package com.ledgerx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ApiIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String createClientApiKey() throws Exception {
        String body = mvc.perform(post("/admin/clients").header("X-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"client-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("apiKey").asText();
    }

    private String createWallet(String apiKey, String owner) throws Exception {
        String body = mvc.perform(post("/wallets").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"" + owner + "\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asText();
    }

    private MockHttpServletRequestBuilder transfer(String apiKey, String key, String from, String to, long amount) {
        return post("/transfers").header("X-API-Key", apiKey).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromWalletId\":\"" + from + "\",\"toWalletId\":\"" + to + "\",\"amount\":" + amount + "}");
    }

    private void deposit(String apiKey, String wallet, long amount) throws Exception {
        mvc.perform(post("/wallets/" + wallet + "/deposit").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void depositTransferAndReadBalances() throws Exception {
        String apiKey = createClientApiKey();
        String alice = createWallet(apiKey, "alice");
        String bob = createWallet(apiKey, "bob");
        deposit(apiKey, alice, 5000);

        mvc.perform(transfer(apiKey, UUID.randomUUID().toString(), alice, bob, 1200))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1200));

        mvc.perform(get("/wallets/" + alice).header("X-API-Key", apiKey)).andExpect(jsonPath("$.balance").value(3800));
        mvc.perform(get("/wallets/" + bob).header("X-API-Key", apiKey)).andExpect(jsonPath("$.balance").value(1200));
    }

    @Test
    void retryWithSameKeyReplaysAndMovesMoneyOnce() throws Exception {
        String apiKey = createClientApiKey();
        String alice = createWallet(apiKey, "alice");
        String bob = createWallet(apiKey, "bob");
        deposit(apiKey, alice, 1000);

        mvc.perform(transfer(apiKey, "key-1", alice, bob, 400)).andExpect(status().isCreated());
        mvc.perform(transfer(apiKey, "key-1", alice, bob, 400))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/wallets/" + alice).header("X-API-Key", apiKey)).andExpect(jsonPath("$.balance").value(600));
        mvc.perform(get("/wallets/" + bob).header("X-API-Key", apiKey)).andExpect(jsonPath("$.balance").value(400));

        mvc.perform(transfer(apiKey, "key-1", alice, bob, 401)).andExpect(status().isConflict());
    }

    @Test
    void sameKeyStringIsIndependentPerClient() throws Exception {
        String apiKeyA = createClientApiKey();
        String apiKeyB = createClientApiKey();
        String a1 = createWallet(apiKeyA, "a1");
        String a2 = createWallet(apiKeyA, "a2");
        String b1 = createWallet(apiKeyB, "b1");
        String b2 = createWallet(apiKeyB, "b2");
        deposit(apiKeyA, a1, 100);
        deposit(apiKeyB, b1, 100);

        mvc.perform(transfer(apiKeyA, "shared-key", a1, a2, 10)).andExpect(status().isCreated());
        mvc.perform(transfer(apiKeyB, "shared-key", b1, b2, 10)).andExpect(status().isCreated());
    }

    @Test
    void requestsWithoutValidApiKeyReturn401() throws Exception {
        mvc.perform(get("/wallets/00000000-0000-0000-0000-000000000042")).andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/00000000-0000-0000-0000-000000000042").header("X-API-Key", "lx_wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientCannotSeeDepositToOrSpendFromAnotherClientsWallet() throws Exception {
        String apiKeyA = createClientApiKey();
        String apiKeyB = createClientApiKey();
        String aliceWallet = createWallet(apiKeyA, "alice");
        String bobWallet = createWallet(apiKeyB, "bob");
        deposit(apiKeyA, aliceWallet, 1000);

        mvc.perform(get("/wallets/" + aliceWallet).header("X-API-Key", apiKeyB)).andExpect(status().isNotFound());
        mvc.perform(post("/wallets/" + aliceWallet + "/deposit").header("X-API-Key", apiKeyB)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}")).andExpect(status().isNotFound());
        // B tries to pull money out of A's wallet.
        mvc.perform(transfer(apiKeyB, UUID.randomUUID().toString(), aliceWallet, bobWallet, 500))
                .andExpect(status().isNotFound());

        mvc.perform(get("/wallets/" + aliceWallet).header("X-API-Key", apiKeyA)).andExpect(jsonPath("$.balance").value(1000));
    }

    @Test
    void clientCanSendToAnotherClientsWallet() throws Exception {
        String apiKeyA = createClientApiKey();
        String apiKeyB = createClientApiKey();
        String aliceWallet = createWallet(apiKeyA, "alice");
        String bobWallet = createWallet(apiKeyB, "bob");
        deposit(apiKeyA, aliceWallet, 1000);

        mvc.perform(transfer(apiKeyA, UUID.randomUUID().toString(), aliceWallet, bobWallet, 250))
                .andExpect(status().isCreated());
        mvc.perform(get("/wallets/" + bobWallet).header("X-API-Key", apiKeyB)).andExpect(jsonPath("$.balance").value(250));
    }

    @Test
    void adminEndpointRejectsWrongKey() throws Exception {
        mvc.perform(post("/admin/clients").header("X-Admin-Key", "nope")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/clients")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        String apiKey = createClientApiKey();
        mvc.perform(post("/transfers").header("X-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromWalletId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"toWalletId\":\"00000000-0000-0000-0000-000000000002\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        String apiKey = createClientApiKey();
        String alice = createWallet(apiKey, "alice");
        String bob = createWallet(apiKey, "bob");

        mvc.perform(transfer(apiKey, UUID.randomUUID().toString(), alice, bob, 1))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidInputReturns400() throws Exception {
        String apiKey = createClientApiKey();
        mvc.perform(post("/wallets").header("X-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"x\",\"currency\":\"rupees\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/wallets").header("X-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"x\",\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownWalletReturns404() throws Exception {
        String apiKey = createClientApiKey();
        mvc.perform(get("/wallets/00000000-0000-0000-0000-000000000042").header("X-API-Key", apiKey))
                .andExpect(status().isNotFound());
    }
}
