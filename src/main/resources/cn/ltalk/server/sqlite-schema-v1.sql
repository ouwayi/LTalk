CREATE TABLE accounts (
    user_id TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    salt TEXT NOT NULL,
    hash TEXT NOT NULL,
    avatar TEXT NOT NULL,
    created_at TEXT NOT NULL,
    profile_revision INTEGER NOT NULL CHECK (profile_revision >= 0)
) STRICT;

CREATE TABLE relationships (
    kind TEXT NOT NULL CHECK (kind IN ('FRIEND', 'PRIVATE', 'BLOCKED')),
    from_user TEXT NOT NULL REFERENCES accounts(user_id),
    to_user TEXT NOT NULL REFERENCES accounts(user_id),
    PRIMARY KEY (kind, from_user, to_user),
    CHECK (from_user <> to_user)
) STRICT;

CREATE TABLE chat_groups (
    group_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    admin TEXT NOT NULL REFERENCES accounts(user_id),
    review INTEGER NOT NULL CHECK (review IN (0, 1)),
    closed INTEGER NOT NULL CHECK (closed IN (0, 1))
) STRICT;

CREATE TABLE social_requests (
    request_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('FRIEND', 'PRIVATE', 'JOIN', 'INVITE')),
    from_user TEXT NOT NULL REFERENCES accounts(user_id),
    to_user TEXT REFERENCES accounts(user_id),
    group_id TEXT REFERENCES chat_groups(group_id),
    CHECK ((kind = 'JOIN') = (to_user IS NULL)),
    CHECK ((kind IN ('JOIN', 'INVITE')) = (group_id IS NOT NULL))
) STRICT;

CREATE TABLE group_memberships (
    group_id TEXT NOT NULL REFERENCES chat_groups(group_id),
    user_id TEXT NOT NULL REFERENCES accounts(user_id),
    period INTEGER NOT NULL CHECK (period >= 0),
    start_seq INTEGER NOT NULL CHECK (start_seq > 0),
    end_seq INTEGER CHECK (end_seq >= 0),
    PRIMARY KEY (group_id, user_id, period)
) STRICT;

CREATE UNIQUE INDEX group_active_membership ON group_memberships (group_id, user_id) WHERE end_seq IS NULL;
CREATE INDEX group_membership_by_user ON group_memberships (user_id, group_id);

CREATE TABLE messages (
    seq INTEGER PRIMARY KEY,
    message_id TEXT NOT NULL UNIQUE,
    conversation_id TEXT NOT NULL,
    group_id TEXT REFERENCES chat_groups(group_id),
    sender TEXT NOT NULL REFERENCES accounts(user_id),
    recipient TEXT REFERENCES accounts(user_id),
    content TEXT NOT NULL,
    sent_at TEXT NOT NULL,
    reply_to TEXT REFERENCES messages(message_id),
    attachment_kind TEXT CHECK (attachment_kind IN ('IMAGE', 'FILE')),
    attachment_name TEXT,
    attachment_size INTEGER CHECK (attachment_size >= 0),
    thumbnail TEXT,
    CHECK ((attachment_kind IS NULL) = (attachment_name IS NULL)),
    CHECK ((attachment_kind IS NULL) = (attachment_size IS NULL)),
    CHECK (thumbnail IS NULL OR attachment_kind IS 'IMAGE'),
    CHECK ((recipient IS NULL) = (group_id IS NOT NULL OR conversation_id = 'GROUP:PUBLIC'))
) STRICT;

CREATE INDEX messages_by_conversation ON messages (conversation_id, seq);

CREATE TABLE private_visibility (
    user_id TEXT NOT NULL REFERENCES accounts(user_id),
    message_seq INTEGER NOT NULL REFERENCES messages(seq),
    PRIMARY KEY (user_id, message_seq)
) STRICT;

CREATE TABLE hidden_messages (
    user_id TEXT NOT NULL REFERENCES accounts(user_id),
    message_seq INTEGER NOT NULL REFERENCES messages(seq),
    PRIMARY KEY (user_id, message_seq)
) STRICT;

CREATE TABLE history_clears (
    user_id TEXT NOT NULL REFERENCES accounts(user_id),
    conversation_id TEXT NOT NULL,
    through_seq INTEGER NOT NULL CHECK (through_seq >= 0),
    PRIMARY KEY (user_id, conversation_id)
) STRICT;
