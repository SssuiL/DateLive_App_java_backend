ALTER TABLE users ADD COLUMN last_seen_at timestamptz;
ALTER TABLE chat_change_outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0,
 ADD COLUMN next_retry_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN last_error_code varchar(80);
ALTER TABLE notification_change_outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0,
 ADD COLUMN next_retry_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN last_error_code varchar(80);
ALTER TABLE chat_change_outbox DROP CONSTRAINT chat_change_outbox_event_type_check;
ALTER TABLE chat_change_outbox ADD CONSTRAINT chat_change_outbox_event_type_check
 CHECK(event_type IN ('message.created','message.read','message.recalled','message.hidden','conversation.cleared','message.delivered'));
CREATE TABLE realtime_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 recipient_user_id varchar(64) NOT NULL REFERENCES users(id),
 actor_user_id varchar(64) REFERENCES users(id),
 channel varchar(16) NOT NULL CHECK(channel IN ('messages','notifications')),
 event_type varchar(48) NOT NULL, source_key varchar(160) NOT NULL,
 conversation_id varchar(64) REFERENCES conversations(id) ON DELETE CASCADE,
 message_id varchar(64) REFERENCES messages(id) ON DELETE CASCADE,
 payload jsonb NOT NULL DEFAULT '{}', expires_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(recipient_user_id,channel,source_key)
);
CREATE INDEX realtime_recipient_cursor ON realtime_events(recipient_user_id,channel,id);
CREATE TABLE realtime_checkpoints (
 user_id varchar(64) NOT NULL REFERENCES users(id),
 session_id varchar(64) NOT NULL REFERENCES refresh_tokens(id) ON DELETE CASCADE,
 channel varchar(16) NOT NULL, last_ack bigint NOT NULL DEFAULT 0,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(session_id,channel)
);
CREATE TABLE realtime_connections (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL REFERENCES users(id),
 session_id varchar(64) NOT NULL REFERENCES refresh_tokens(id) ON DELETE CASCADE,
 channel varchar(16) NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX realtime_connections_user ON realtime_connections(user_id,expires_at);
