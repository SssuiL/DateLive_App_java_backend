ALTER TABLE user_profiles
 ADD COLUMN age integer CHECK(age BETWEEN 18 AND 120),
 ADD COLUMN zodiac varchar(16), ADD COLUMN occupation varchar(48),
 ADD COLUMN pending_avatar_url varchar(500),
 ADD COLUMN pending_photo_urls jsonb NOT NULL DEFAULT '[]',
 ADD COLUMN moderation_status varchar(24) NOT NULL DEFAULT 'approved',
 ADD COLUMN moderation_reason varchar(240), ADD COLUMN reviewed_at timestamptz,
 ADD COLUMN distance_km double precision CHECK(distance_km>=0 AND distance_km<'Infinity'::float8),
 ADD COLUMN distance_visible boolean NOT NULL DEFAULT true;
-- Metadata boundary only; upload/storage and review workers will extend this table.
CREATE TABLE media_assets (
 id varchar(64) PRIMARY KEY, owner_user_id varchar(64) NOT NULL REFERENCES users(id),
 url varchar(500) NOT NULL UNIQUE, media_type varchar(16) NOT NULL,
 status varchar(24) NOT NULL CHECK(status IN ('uploaded','review_pending','approved','rejected','deleted')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE blocks (
 actor_user_id varchar(64) NOT NULL REFERENCES users(id),
 target_user_id varchar(64) NOT NULL REFERENCES users(id),
 PRIMARY KEY(actor_user_id,target_user_id), CHECK(actor_user_id<>target_user_id)
);
CREATE INDEX blocks_target ON blocks(target_user_id,actor_user_id);
CREATE TABLE profile_reviews (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id varchar(64) NOT NULL REFERENCES users(id), action varchar(24) NOT NULL,
 reason varchar(240), labels jsonb NOT NULL DEFAULT '[]', provider varchar(32) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification_preferences (
 user_id varchar(64) PRIMARY KEY REFERENCES users(id),
 chat_messages_enabled boolean NOT NULL DEFAULT true,
 friend_requests_enabled boolean NOT NULL DEFAULT true,
 matches_enabled boolean NOT NULL DEFAULT true,
 social_notifications_enabled boolean NOT NULL DEFAULT true,
 group_messages_enabled boolean NOT NULL DEFAULT true,
 live_notifications_enabled boolean NOT NULL DEFAULT true,
 official_notifications_enabled boolean NOT NULL DEFAULT true,
 payment_notifications_enabled boolean NOT NULL DEFAULT true,
 night_quiet_enabled boolean NOT NULL DEFAULT false,
 night_quiet_start varchar(5) NOT NULL DEFAULT '22:00' CHECK(night_quiet_start ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
 night_quiet_end varchar(5) NOT NULL DEFAULT '08:00' CHECK(night_quiet_end ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification_events (
 id varchar(64) PRIMARY KEY, deduplication_key varchar(160) UNIQUE,
 recipient_user_id varchar(64) NOT NULL REFERENCES users(id),
 actor_user_id varchar(64) REFERENCES users(id),
 event_type varchar(32) NOT NULL, category varchar(32) NOT NULL,
 source_type varchar(32), source_id varchar(64), conversation_id varchar(64),
 title varchar(120) NOT NULL, body varchar(240) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'pending', suppress_reason varchar(120),
 delivery_channel varchar(24) NOT NULL DEFAULT 'offline_push',
 payload jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(payload)='object'),
 read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_recipient_time ON notification_events(recipient_user_id,created_at DESC,id DESC);
CREATE INDEX notification_unread ON notification_events(recipient_user_id) WHERE read_at IS NULL;
CREATE TABLE notification_change_outbox (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id varchar(64) NOT NULL REFERENCES users(id), event_type varchar(48) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), delivered_at timestamptz
);
CREATE INDEX notification_outbox_pending ON notification_change_outbox(id) WHERE delivered_at IS NULL;
