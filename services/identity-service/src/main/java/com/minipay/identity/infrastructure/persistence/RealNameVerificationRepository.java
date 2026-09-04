package com.minipay.identity.infrastructure.persistence;

import com.minipay.identity.application.service.RealNameVerificationRejectedException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RealNameVerificationRepository {
    private final JdbcTemplate jdbcTemplate;

    public RealNameVerificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VerificationRow> findByIdempotency(UUID userId, String key) {
        return jdbcTemplate.query("""
                SELECT verification_id, user_id, request_hash, legal_name_masked,
                       id_number_masked, provider, provider_reference, status,
                       failure_code, verified_at, created_at, updated_at
                FROM real_name_verification
                WHERE user_id = ? AND idempotency_key = ?
                """, rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId), key);
    }

    public Optional<VerificationRow> find(UUID userId, UUID verificationId) {
        return jdbcTemplate.query("""
                SELECT verification_id, user_id, request_hash, legal_name_masked,
                       id_number_masked, provider, provider_reference, status,
                       failure_code, verified_at, created_at, updated_at
                FROM real_name_verification
                WHERE user_id = ? AND verification_id = ?
                """, rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId),
                AdminAccountRepository.uuidToBytes(verificationId));
    }

    /** Returns only an already-masked name from a verified record for the account owner. */
    public Optional<VerificationRow> findLatestVerified(UUID userId) {
        return jdbcTemplate.query("""
                SELECT verification_id, user_id, request_hash, legal_name_masked,
                       id_number_masked, provider, provider_reference, status,
                       failure_code, verified_at, created_at, updated_at
                FROM real_name_verification
                WHERE user_id = ? AND status = 'VERIFIED'
                ORDER BY verified_at DESC, created_at DESC
                LIMIT 1
                """, rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                AdminAccountRepository.uuidToBytes(userId));
    }

    public void insert(
            UUID id, UUID userId, String key, byte[] requestHash,
            String nameMasked, byte[] nameHash, String idMasked, byte[] idHash,
            String provider) {
        jdbcTemplate.update("""
                INSERT INTO real_name_verification (
                  verification_id, user_id, idempotency_key, request_hash,
                  legal_name_masked, legal_name_hash, id_number_masked, id_number_hash,
                  provider, provider_reference, status, failure_code, verified_at,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PROCESSING', NULL, NULL,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                AdminAccountRepository.uuidToBytes(id),
                AdminAccountRepository.uuidToBytes(userId), key, requestHash,
                nameMasked, nameHash, idMasked, idHash, provider);
    }

    public void complete(UUID id, boolean verified, String reference, String failureCode) {
        int changed = jdbcTemplate.update("""
                UPDATE real_name_verification
                SET status = ?, provider_reference = ?, failure_code = ?,
                    verified_at = CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE verification_id = ? AND status = 'PROCESSING'
                """, verified ? "VERIFIED" : "REJECTED", reference, failureCode,
                verified, AdminAccountRepository.uuidToBytes(id));
        if (changed != 1) {
            throw new IllegalStateException("Real-name verification did not converge");
        }
    }

    public String currentStatus(UUID userId) {
        return jdbcTemplate.query("""
                SELECT status FROM real_name_verification
                WHERE user_id = ?
                ORDER BY status = 'VERIFIED' DESC, created_at DESC
                LIMIT 1
                """, rs -> rs.next() ? rs.getString("status") : "UNVERIFIED",
                AdminAccountRepository.uuidToBytes(userId));
    }

    public VerificationRow requireSameRequest(
            VerificationRow row, byte[] requestHash) {
        if (!Arrays.equals(row.requestHash(), requestHash)) {
            throw new RealNameVerificationRejectedException("IDEMPOTENCY_KEY_REUSED");
        }
        return row;
    }

    private VerificationRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VerificationRow(
                AdminAccountRepository.bytesToUuid(rs.getBytes("verification_id")),
                AdminAccountRepository.bytesToUuid(rs.getBytes("user_id")),
                rs.getBytes("request_hash"), rs.getString("legal_name_masked"),
                rs.getString("id_number_masked"), rs.getString("provider"),
                rs.getString("provider_reference"), rs.getString("status"),
                rs.getString("failure_code"),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public record VerificationRow(
            UUID verificationId, UUID userId, byte[] requestHash,
            String legalNameMasked, String idNumberMasked, String provider,
            String providerReference, String status, String failureCode,
            Instant verifiedAt, Instant createdAt, Instant updatedAt) {
    }
}
