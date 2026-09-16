CREATE TABLE messages (
 id varchar(64) PRIMARY KEY,
 conversation_id varchar(64) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 sender_id varchar(64) NOT NULL REFERENCES users(id),
 client_message_id varchar(64),
 reply_to_message_id varchar(64),
 type varchar(16) NOT NULL DEFAULT 'text' CHECK(type='text'),
 content varchar(2000) NOT NULL CHECK(char_length(content)>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(conversation_id,sender_id,client_message_id),
 UNIQUE(id,conversation_id),
 FOREIGN KEY(conversation_id,sender_id) REFERENCES conversation_member_states(conversation_id,user_id),
 FOREIGN KEY(reply_to_message_id,conversation_id) REFERENCES messages(id,conversation_id)
);
CREATE INDEX messages_conversation_time ON messages(conversation_id,created_at DESC,id DESC);
CREATE INDEX messages_sender ON messages(sender_id);
CREATE TABLE message_receipts (
 message_id varchar(64) NOT NULL, conversation_id varchar(64) NOT NULL,
 user_id varchar(64) NOT NULL REFERENCES users(id),
 delivered_at timestamptz, read_at timestamptz,
 PRIMARY KEY(message_id,user_id),
 FOREIGN KEY(message_id,conversation_id) REFERENCES messages(id,conversation_id) ON DELETE CASCADE,
 FOREIGN KEY(conversation_id,user_id) REFERENCES conversation_member_states(conversation_id,user_id) ON DELETE CASCADE
);
CREATE INDEX message_receipts_unread ON message_receipts(conversation_id,user_id) WHERE read_at IS NULL;
CREATE TABLE chat_change_outbox (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 conversation_id varchar(64) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 actor_user_id varchar(64) NOT NULL REFERENCES users(id),
 message_id varchar(64) REFERENCES messages(id) ON DELETE CASCADE,
 event_type varchar(48) NOT NULL CHECK(event_type IN ('message.created','message.read')),
 payload jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), delivered_at timestamptz
);
CREATE INDEX chat_outbox_pending ON chat_change_outbox(id) WHERE delivered_at IS NULL;
