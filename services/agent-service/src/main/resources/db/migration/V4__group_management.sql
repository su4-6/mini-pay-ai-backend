ALTER TABLE chat_group ADD COLUMN owner_id BINARY(16) NULL AFTER name;

UPDATE chat_group g
SET owner_id = (
    SELECT gm.user_id FROM chat_group_member gm
    WHERE gm.group_id = g.id
    ORDER BY gm.joined_at
    LIMIT 1
)
WHERE owner_id IS NULL;

ALTER TABLE chat_group MODIFY COLUMN owner_id BINARY(16) NOT NULL;
ALTER TABLE chat_group_member ADD COLUMN nickname VARCHAR(128) NULL AFTER user_id;
