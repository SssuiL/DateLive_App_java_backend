-- Java-owned, isolated database. Never run this against the Python database.
CREATE TABLE users (
 id varchar(64) PRIMARY KEY,
 phone varchar(32) NOT NULL UNIQUE,
 nickname varchar(32) NOT NULL,
 password_hash varchar(255) NOT NULL,
 status varchar(32) NOT NULL DEFAULT 'active'
   CHECK (status IN ('active','deactivation_pending','deactivated','banned')),
 is_paid_member boolean NOT NULL DEFAULT false,
 membership_status varchar(32) NOT NULL DEFAULT 'free',
 deactivation_requested_at timestamptz,
 deactivation_due_at timestamptz,
 deactivated_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_profiles (
 user_id varchar(64) PRIMARY KEY REFERENCES users(id),
 nickname varchar(32) NOT NULL,
 avatar_url varchar(500),
 bio varchar(240),
 tags jsonb NOT NULL DEFAULT '[]',
 hobbies jsonb NOT NULL DEFAULT '[]',
 photo_urls jsonb NOT NULL DEFAULT '[]'
);
CREATE TABLE refresh_tokens (
 id varchar(64) PRIMARY KEY,
 user_id varchar(64) NOT NULL REFERENCES users(id),
 token_hash varchar(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 revoked_reason varchar(64),
 replaced_by_token_id varchar(64) REFERENCES refresh_tokens(id),
 device_id varchar(128),
 device_name varchar(120),
 platform varchar(32),
 last_used_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_active_user ON refresh_tokens(user_id, last_used_at DESC)
 WHERE revoked_at IS NULL;
CREATE TABLE auth_security_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id varchar(64) REFERENCES users(id),
 session_id varchar(64),
 event_type varchar(48) NOT NULL,
 outcome varchar(16) NOT NULL,
 reason_code varchar(64),
 request_id varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_events_user_time ON auth_security_events(user_id, created_at DESC);
CREATE TABLE auth_rate_windows (
 bucket_key varchar(160) PRIMARY KEY,
 hits integer NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE INDEX auth_rate_windows_expiry ON auth_rate_windows(expires_at);
