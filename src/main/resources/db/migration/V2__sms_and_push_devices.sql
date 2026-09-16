CREATE TABLE auth_verification_codes (
 id varchar(64) PRIMARY KEY, phone varchar(32) NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose IN ('register','login','password_reset')),
 code_hash varchar(64) NOT NULL, delivery_state varchar(16) NOT NULL CHECK(delivery_state IN ('pending','sent','failed')),
 provider varchar(32), provider_message_id varchar(128),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 consumed_at timestamptz, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_codes_phone_purpose_created ON auth_verification_codes(phone,purpose,created_at DESC);
CREATE TABLE push_devices (
 id varchar(64) PRIMARY KEY, user_id varchar(64) NOT NULL REFERENCES users(id),
 device_id varchar(128) NOT NULL, platform varchar(32) NOT NULL,
 push_token varchar(512) NOT NULL, provider varchar(32) NOT NULL,
 enabled boolean NOT NULL DEFAULT true, last_registered_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,device_id)
);
CREATE INDEX auth_codes_created ON auth_verification_codes(created_at);
