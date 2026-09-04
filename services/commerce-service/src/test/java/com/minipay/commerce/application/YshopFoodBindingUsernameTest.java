package com.minipay.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.infrastructure.identity.IdentityDisclosureGateway;
import com.minipay.commerce.infrastructure.yshop.YshopGateway;
import com.minipay.commerce.infrastructure.yshop.YshopModels.IdentityView;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class YshopFoodBindingUsernameTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lazilyBackfillsUsernameForAnExistingActiveBinding() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        YshopGateway yshop = mock(YshopGateway.class);
        UUID userId = UUID.fromString("019f0000-0000-7000-8000-000000000001");
        UUID bindingId = UUID.fromString("019f0000-0000-7000-8000-000000000002");
        AtomicInteger reads = new AtomicInteger();

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet row = bindingRow(bindingId, userId, reads.getAndIncrement() == 0
                            ? null : "external_user");
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(yshop.identity(userId.toString())).thenReturn(new IdentityView(
                "MINIPAY", userId.toString(), 42L, "external_user", false));

        var service = new YshopFoodIntegrationService(
                jdbc, new ObjectMapper(), yshop, mock(IdentityDisclosureGateway.class),
                "yshop", "http://127.0.0.1:4173");

        var result = service.binding(userId);

        assertThat(result.username()).isEqualTo("external_user");
        verify(yshop).identity(userId.toString());
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void keepsTheStoredBindingWhenUsernameBackfillFails() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        YshopGateway yshop = mock(YshopGateway.class);
        UUID userId = UUID.fromString("019f0000-0000-7000-8000-000000000001");
        UUID bindingId = UUID.fromString("019f0000-0000-7000-8000-000000000002");

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(bindingRow(bindingId, userId, null), 0));
                });
        when(yshop.identity(userId.toString())).thenThrow(
                new CommerceApplicationException("YSHOP_UNAVAILABLE", "upstream unavailable"));

        var result = service(jdbc, yshop).binding(userId);

        assertThat(result.active()).isTrue();
        assertThat(result.username()).isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reusesAPersistedUsernameWithoutCallingYshop() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        YshopGateway yshop = mock(YshopGateway.class);
        UUID userId = UUID.fromString("019f0000-0000-7000-8000-000000000001");
        UUID bindingId = UUID.fromString("019f0000-0000-7000-8000-000000000002");

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(
                            bindingRow(bindingId, userId, "external_user"), 0));
                });

        var result = service(jdbc, yshop).binding(userId);

        assertThat(result.username()).isEqualTo("external_user");
        verify(yshop, never()).identity(anyString());
    }

    private static YshopFoodIntegrationService service(JdbcTemplate jdbc, YshopGateway yshop) {
        return new YshopFoodIntegrationService(
                jdbc, new ObjectMapper(), yshop, mock(IdentityDisclosureGateway.class),
                "yshop", "http://127.0.0.1:4173");
    }

    private static ResultSet bindingRow(UUID bindingId, UUID userId, String username)
            throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("binding_id")).thenReturn(bytes(bindingId));
        when(row.getString("provider")).thenReturn("YSHOP");
        when(row.getString("provider_subject")).thenReturn(userId.toString());
        when(row.getString("provider_username")).thenReturn(username);
        when(row.getString("status")).thenReturn("ACTIVE");
        when(row.getString("profile_sync_status")).thenReturn("SYNCED");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
        return row;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
