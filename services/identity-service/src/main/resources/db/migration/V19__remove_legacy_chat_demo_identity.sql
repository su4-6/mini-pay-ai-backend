-- This UUID and login name were created only by docker/seed-chat-test-data.sql.
-- Require both values to match before deleting anything so a reused UUID or
-- login name cannot remove a real account.
SET @legacy_chat_demo_user_id = (
    SELECT user_id
    FROM user_profile
    WHERE user_id = UNHEX(REPLACE('b2c3d4e5-f6a7-8901-bcde-f12345678901', '-', ''))
      AND login_name = 'linxia-demo'
    LIMIT 1
);

DELETE FROM friend_request
WHERE from_user_id = @legacy_chat_demo_user_id
   OR to_user_id = @legacy_chat_demo_user_id;

DELETE FROM friend_relation
WHERE user_id = @legacy_chat_demo_user_id
   OR friend_id = @legacy_chat_demo_user_id;

DELETE FROM avatar_upload WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM consumer_email_contact WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM consumer_onboarding_request WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM login_audit WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM payment_authorization WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM real_name_verification WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM user_credential WHERE user_id = @legacy_chat_demo_user_id;
DELETE FROM user_role WHERE user_id = @legacy_chat_demo_user_id;

-- V13 existed in some local databases but is absent from older clean baselines.
-- Delete search rows only when that historical table is present.
SET @legacy_search_cleanup_sql = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'search_history'
    ),
    'DELETE FROM search_history WHERE user_id = @legacy_chat_demo_user_id',
    'SELECT 1'
);
PREPARE legacy_search_cleanup FROM @legacy_search_cleanup_sql;
EXECUTE legacy_search_cleanup;
DEALLOCATE PREPARE legacy_search_cleanup;

DELETE FROM user_profile
WHERE user_id = @legacy_chat_demo_user_id
  AND login_name = 'linxia-demo';

SET @legacy_search_cleanup_sql = NULL;
SET @legacy_chat_demo_user_id = NULL;
