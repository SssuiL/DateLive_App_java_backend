ALTER TABLE blocks
 ADD COLUMN id varchar(64) NOT NULL DEFAULT ('block_' || replace(gen_random_uuid()::text,'-','')),
 ADD COLUMN block_type varchar(24) NOT NULL DEFAULT 'block' CHECK(block_type IN ('block','hide_from_explore')),
 ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
 ADD CONSTRAINT blocks_id_unique UNIQUE(id);
CREATE INDEX blocks_actor_time ON blocks(actor_user_id,created_at DESC,id DESC);
CREATE TABLE friend_requests (
 id varchar(64) PRIMARY KEY,
 requester_id varchar(64) NOT NULL REFERENCES users(id),
 receiver_id varchar(64) NOT NULL REFERENCES users(id),
 message varchar(120), status varchar(16) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','rejected')),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(requester_id<>receiver_id)
);
CREATE UNIQUE INDEX friend_requests_pending_pair ON friend_requests(requester_id,receiver_id) WHERE status='pending';
CREATE INDEX friend_requests_requester_time ON friend_requests(requester_id,created_at DESC,id DESC);
CREATE INDEX friend_requests_receiver_time ON friend_requests(receiver_id,created_at DESC,id DESC);
-- Conversation foundation for accepting friends. Message and conversation HTTP APIs come in the chat migration.
CREATE TABLE conversations (
 id varchar(64) PRIMARY KEY, type varchar(16) NOT NULL,
 title varchar(100) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 last_active_at timestamptz NOT NULL DEFAULT now(), last_message varchar(500), expires_at timestamptz
);
CREATE TABLE conversation_member_states (
 id varchar(64) PRIMARY KEY,
 conversation_id varchar(64) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 user_id varchar(64) NOT NULL REFERENCES users(id),
 pinned boolean NOT NULL DEFAULT false, muted boolean NOT NULL DEFAULT false,
 unread_count integer NOT NULL DEFAULT 0 CHECK(unread_count>=0),
 updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(conversation_id,user_id)
);
CREATE INDEX conversation_members_user ON conversation_member_states(user_id,conversation_id);
CREATE TABLE friendships (
 id varchar(64) PRIMARY KEY,
 user_a_id varchar(64) NOT NULL REFERENCES users(id),
 user_b_id varchar(64) NOT NULL REFERENCES users(id),
 conversation_id varchar(64) NOT NULL REFERENCES conversations(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_a_id,user_b_id), CHECK(user_a_id<user_b_id)
);
CREATE INDEX friendships_user_b ON friendships(user_b_id,user_a_id);
