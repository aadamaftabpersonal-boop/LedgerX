package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AdminOutboxIT extends AbstractPostgresIT {

    private static final String ADMIN = "test-admin-key";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbox_events").update();
    }

    private void insertEvent(boolean dead) {
        jdbc.sql("""
                INSERT INTO outbox_events (event_type, aggregate_id, payload, dead, attempts)
                VALUES ('transfer.completed', :agg, '{}'::jsonb, :dead, :attempts)
                """).param("agg", UUID.randomUUID()).param("dead", dead).param("attempts", dead ? 10 : 0).update();
    }

    @Test
    void reportsThePendingAndDeadBacklog() throws Exception {
        insertEvent(false);
        insertEvent(false);
        insertEvent(true);

        mvc.perform(get("/admin/outbox").header("X-Admin-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.dead").value(1));
    }

    @Test
    void requeuingMovesDeadEventsBackIntoTheQueue() throws Exception {
        insertEvent(true);
        insertEvent(true);

        mvc.perform(post("/admin/outbox/requeue-dead").header("X-Admin-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(2));

        mvc.perform(get("/admin/outbox").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.dead").value(0));
        Integer reset = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE attempts = 0 AND NOT dead")
                .query(Integer.class).single();
        assertThat(reset).isEqualTo(2);
    }

    @Test
    void bothEndpointsRequireTheAdminKey() throws Exception {
        mvc.perform(get("/admin/outbox")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/outbox").header("X-Admin-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/outbox/requeue-dead")).andExpect(status().isForbidden());
    }
}
