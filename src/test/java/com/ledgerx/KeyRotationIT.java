package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class KeyRotationIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private String newClientKey() throws Exception {
        String body = mvc.perform(post("/admin/clients").header("X-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"keys-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("apiKey").asText();
    }

    private String rotate(String key, String body) throws Exception {
        String response = mvc.perform(post("/keys/rotate").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("apiKey").asText();
    }

    private void assertWorks(String key) throws Exception {
        mvc.perform(get("/keys").header("X-API-Key", key)).andExpect(status().isOk());
    }

    private void assertRejected(String key) throws Exception {
        mvc.perform(get("/keys").header("X-API-Key", key)).andExpect(status().isUnauthorized());
    }

    private JsonNode keyList(String key) throws Exception {
        return json.readTree(mvc.perform(get("/keys").header("X-API-Key", key))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void bothKeysWorkDuringTheGracePeriod() throws Exception {
        String oldKey = newClientKey();
        String newKey = rotate(oldKey, "{\"gracePeriodSeconds\":3600}");

        assertThat(newKey).isNotEqualTo(oldKey).startsWith("lx_");
        assertWorks(oldKey);
        assertWorks(newKey);
    }

    @Test
    void theOldKeyStopsWorkingOnceItsGracePeriodEnds() throws Exception {
        String oldKey = newClientKey();
        String newKey = rotate(oldKey, "{\"gracePeriodSeconds\":3600}");

        jdbc.sql("UPDATE api_keys SET expires_at = now() - interval '1 second' WHERE expires_at IS NOT NULL").update();

        assertRejected(oldKey);
        assertWorks(newKey);
    }

    @Test
    void zeroGraceInvalidatesTheOldKeyImmediately() throws Exception {
        String oldKey = newClientKey();
        String newKey = rotate(oldKey, "{\"gracePeriodSeconds\":0}");

        assertRejected(oldKey);
        assertWorks(newKey);
    }

    @Test
    void rotatingWithoutABodyUsesTheDefaultGracePeriod() throws Exception {
        String oldKey = newClientKey();
        String response = mvc.perform(post("/keys/rotate").header("X-API-Key", oldKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.previousKeysExpireAt").exists())
                .andReturn().getResponse().getContentAsString();

        assertWorks(oldKey);
        assertWorks(json.readTree(response).get("apiKey").asText());
    }

    @Test
    void rotatingAgainDoesNotExtendAnExpiringKey() throws Exception {
        String first = newClientKey();
        String second = rotate(first, "{\"gracePeriodSeconds\":60}");
        rotate(second, "{\"gracePeriodSeconds\":604800}");

        JsonNode keys = keyList(second);
        assertThat(keys).hasSize(3);
        String firstId = keys.get(0).get("id").asText();
        String secondId = keys.get(1).get("id").asText();

        // The first key keeps its original 60 s deadline; the week granted by the second rotation applies only
        // to the key that had no deadline yet.
        Boolean firstStillShort = jdbc.sql("SELECT expires_at < now() + interval '2 minutes' FROM api_keys WHERE id = CAST(:id AS uuid)")
                .param("id", firstId).query(Boolean.class).single();
        Boolean secondIsLong = jdbc.sql("SELECT expires_at > now() + interval '6 days' FROM api_keys WHERE id = CAST(:id AS uuid)")
                .param("id", secondId).query(Boolean.class).single();
        assertThat(firstStillShort).isTrue();
        assertThat(secondIsLong).isTrue();
    }

    @Test
    void listingShowsMetadataButNeverSecrets() throws Exception {
        String key = newClientKey();
        rotate(key, "{\"gracePeriodSeconds\":3600}");

        JsonNode keys = keyList(key);

        assertThat(keys).hasSize(2);
        for (JsonNode k : keys) {
            assertThat(k.has("id")).isTrue();
            assertThat(k.has("active")).isTrue();
            assertThat(k.toString()).doesNotContain("lx_").doesNotContain("hash");
        }
    }

    @Test
    void aKeyCanBeRevokedImmediatelyButNotTheLastActiveOne() throws Exception {
        String oldKey = newClientKey();
        String newKey = rotate(oldKey, "{\"gracePeriodSeconds\":3600}");
        JsonNode keys = keyList(newKey);
        String oldKeyId = keys.get(0).get("id").asText();
        String newKeyId = keys.get(1).get("id").asText();

        mvc.perform(delete("/keys/" + oldKeyId).header("X-API-Key", newKey)).andExpect(status().isNoContent());
        assertRejected(oldKey);

        mvc.perform(delete("/keys/" + newKeyId).header("X-API-Key", newKey)).andExpect(status().isConflict());
        assertWorks(newKey);
    }

    @Test
    void clientsCannotSeeOrRevokeEachOthersKeys() throws Exception {
        String keyA = newClientKey();
        String keyB = newClientKey();
        String keyIdOfA = keyList(keyA).get(0).get("id").asText();

        mvc.perform(delete("/keys/" + keyIdOfA).header("X-API-Key", keyB)).andExpect(status().isNotFound());
        assertThat(keyList(keyB)).hasSize(1);
        assertWorks(keyA);
    }

    @Test
    void invalidGracePeriodsAreRejected() throws Exception {
        String key = newClientKey();

        mvc.perform(post("/keys/rotate").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gracePeriodSeconds\":-5}")).andExpect(status().isBadRequest());
        mvc.perform(post("/keys/rotate").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gracePeriodSeconds\":999999999}")).andExpect(status().isBadRequest());
    }

    @Test
    void rotationRequiresAuthentication() throws Exception {
        mvc.perform(post("/keys/rotate")).andExpect(status().isUnauthorized());
    }
}
