-- Remove the hand-seeded conversations that used non-UUID contact identifiers.
-- The statements are intentionally idempotent so existing local databases and
-- clean installations converge on the same state.
DELETE FROM chat_voice_media
WHERE conversation_id IN ('linxia', 'zhouning', 'chenmo');

DELETE FROM voice_call
WHERE conversation_id IN ('linxia', 'zhouning', 'chenmo');

DELETE FROM chat_conversation_unread
WHERE conversation_id IN ('linxia', 'zhouning', 'chenmo');

DELETE FROM chat_message
WHERE conversation_id IN ('linxia', 'zhouning', 'chenmo');

DELETE FROM chat_conversation
WHERE id IN ('linxia', 'zhouning', 'chenmo');
