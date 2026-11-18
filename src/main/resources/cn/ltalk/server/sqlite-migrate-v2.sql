PRAGMA defer_foreign_keys = ON;

CREATE TEMP TABLE group_numbers (
    old_id TEXT PRIMARY KEY,
    new_id TEXT NOT NULL UNIQUE CHECK (length(new_id) = 10)
) STRICT;

INSERT INTO group_numbers
SELECT group_id, CAST(999999999 + row_number() OVER (ORDER BY group_id) AS TEXT) FROM chat_groups;

UPDATE chat_groups
SET group_id = (SELECT new_id FROM group_numbers WHERE old_id = chat_groups.group_id);

UPDATE social_requests
SET group_id = (SELECT new_id FROM group_numbers WHERE old_id = social_requests.group_id)
WHERE group_id IS NOT NULL;

UPDATE group_memberships
SET group_id = (SELECT new_id FROM group_numbers WHERE old_id = group_memberships.group_id);

UPDATE messages
SET conversation_id = 'GROUP:' || (SELECT new_id FROM group_numbers WHERE old_id = messages.group_id),
    group_id = (SELECT new_id FROM group_numbers WHERE old_id = messages.group_id)
WHERE group_id IS NOT NULL;

UPDATE history_clears
SET conversation_id = 'GROUP:' || (SELECT new_id FROM group_numbers WHERE old_id = substr(conversation_id, 7))
WHERE substr(conversation_id, 7) IN (SELECT old_id FROM group_numbers)
  AND substr(conversation_id, 1, 6) = 'GROUP:';

DROP TABLE group_numbers;

ALTER TABLE messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0 CHECK (recalled IN (0, 1));
