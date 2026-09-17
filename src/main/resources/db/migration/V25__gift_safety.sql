CREATE TABLE gift_safety_settings (
 user_id varchar(64) PRIMARY KEY REFERENCES users(id),age_status varchar(16) NOT NULL DEFAULT 'unverified' CHECK(age_status IN ('unverified','adult','minor')),
 single_limit_coins bigint CHECK(single_limit_coins BETWEEN 1 AND 100000),daily_limit_coins bigint CHECK(daily_limit_coins BETWEEN 1 AND 500000),
 reminder_enabled boolean NOT NULL DEFAULT true,reminder_threshold_coins bigint NOT NULL DEFAULT 500 CHECK(reminder_threshold_coins BETWEEN 1 AND 100000),
 ranking_consent boolean NOT NULL DEFAULT false,first_gift_settings_offered_at timestamptz,updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE gift_risk_events (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL REFERENCES users(id),room_id varchar(64) REFERENCES live_rooms(id) ON DELETE SET NULL,
 gift_record_id varchar(64),event_type varchar(48) NOT NULL,severity varchar(16) NOT NULL DEFAULT 'medium',
 status varchar(16) NOT NULL DEFAULT 'open' CHECK(status IN ('open','reviewed','dismissed','confirmed')),amount_coins bigint NOT NULL DEFAULT 0 CHECK(amount_coins>=0),
 details jsonb NOT NULL DEFAULT '{}',resolution varchar(500),reviewer_admin_id varchar(64) REFERENCES admin_users(id),resolved_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gift_risk_user_time ON gift_risk_events(user_id,created_at DESC);
