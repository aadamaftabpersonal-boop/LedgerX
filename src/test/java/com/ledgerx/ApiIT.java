package com.ledgerx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String createWallet(String owner) throws Exception {
        String body = mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"" + owner + "\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asText();
    }

    @Test
    void depositTransferAndReadBalances() throws Exception {
        String alice = createWallet("alice");
        String bob = createWallet("bob");

        mvc.perform(post("/wallets/" + alice + "/deposit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/transfers").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromWalletId\":\"" + alice + "\",\"toWalletId\":\"" + bob + "\",\"amount\":1200}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1200));

        mvc.perform(get("/wallets/" + alice)).andExpect(jsonPath("$.balance").value(3800));
        mvc.perform(get("/wallets/" + bob)).andExpect(jsonPath("$.balance").value(1200));
    }

    @Test
    void retryWithSameKeyReplaysAndMovesMoneyOnce() throws Exception {
        String alice = createWallet("alice");
        String bob = createWallet("bob");
        mvc.perform(post("/wallets/" + alice + "/deposit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000}")).andExpect(status().isCreated());
        String body = "{\"fromWalletId\":\"" + alice + "\",\"toWalletId\":\"" + bob + "\",\"amount\":400}";

        mvc.perform(post("/transfers").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/transfers").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/wallets/" + alice)).andExpect(jsonPath("$.balance").value(600));
        mvc.perform(get("/wallets/" + bob)).andExpect(jsonPath("$.balance").value(400));

        String different = body.replace("400", "401");
        mvc.perform(post("/transfers").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(different)).andExpect(status().isConflict());
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromWalletId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"toWalletId\":\"00000000-0000-0000-0000-000000000002\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        String alice = createWallet("alice");
        String bob = createWallet("bob");

        mvc.perform(post("/transfers").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromWalletId\":\"" + alice + "\",\"toWalletId\":\"" + bob + "\",\"amount\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidInputReturns400() throws Exception {
        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"x\",\"currency\":\"rupees\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"x\",\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownWalletReturns404() throws Exception {
        mvc.perform(get("/wallets/00000000-0000-0000-0000-000000000042")).andExpect(status().isNotFound());
    }
}
