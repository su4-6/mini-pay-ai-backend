package com.minipay.identity.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemAdminAuditQueryTest {

    @Test
    void buildsParameterizedAuditFiltersInStableOrder() {
        UUID actor = UUID.fromString("018f0000-0000-7000-8000-000000000001");
        Instant from = Instant.parse("2026-08-08T00:00:00Z");
        Instant to = Instant.parse("2026-08-09T00:00:00Z");

        SystemAdminController.AuditQuery query = SystemAdminController.auditQuery(
                actor, " ACCOUNT_UNLOCK ", "ACCOUNT", "SUCCEEDED", "req-1", from, to);

        assertEquals(" WHERE 1=1 AND actor_user_id=? AND action_code=? AND target_type=?"
                + " AND result_code=? AND request_id=? AND occurred_at>=? AND occurred_at<=?", query.where());
        assertEquals(7, query.args().size());
        assertTrue(query.args().get(0) instanceof byte[]);
        assertEquals("ACCOUNT_UNLOCK", query.args().get(1));
        assertEquals("ACCOUNT", query.args().get(2));
        assertEquals("SUCCEEDED", query.args().get(3));
        assertEquals("req-1", query.args().get(4));
        assertEquals(Timestamp.from(from), query.args().get(5));
        assertEquals(Timestamp.from(to), query.args().get(6));
    }

    @Test
    void ignoresBlankOptionalFilters() {
        SystemAdminController.AuditQuery query = SystemAdminController.auditQuery(
                null, " ", null, "", null, null, null);

        assertEquals(" WHERE 1=1", query.where());
        assertTrue(query.args().isEmpty());
    }
}
