ALTER TABLE chat_group_member
    ADD COLUMN original_nickname VARCHAR(128) NULL AFTER nickname;
