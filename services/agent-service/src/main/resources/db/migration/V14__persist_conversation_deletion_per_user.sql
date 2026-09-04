ALTER TABLE chat_conversation_unread
    ADD COLUMN deleted_through_message_id BIGINT NULL AFTER unread_count;
