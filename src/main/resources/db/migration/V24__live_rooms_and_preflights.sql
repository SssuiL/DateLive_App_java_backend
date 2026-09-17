CREATE TABLE live_rooms (
 id varchar(64) PRIMARY KEY,host_user_id varchar(64) NOT NULL REFERENCES users(id),title varchar(100) NOT NULL,category varchar(32) NOT NULL,
 tags jsonb NOT NULL DEFAULT '[]',heat bigint NOT NULL DEFAULT 0,like_count bigint NOT NULL DEFAULT 0,
 distance_km double precision,cover_url varchar(800),status varchar(16) NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','live','ended','closed')),
 stream_provider varchar(32) NOT NULL,provider_room_id varchar(128),stream_state varchar(24) NOT NULL DEFAULT 'idle',
 stream_interrupted_at timestamptz,stream_recovery_deadline_at timestamptz,started_at timestamptz,ended_at timestamptz,
 announcement varchar(500),slow_mode_seconds integer NOT NULL DEFAULT 0,allow_cohost boolean NOT NULL DEFAULT true,
 close_reason varchar(500),closed_by_user_id varchar(64),created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX live_one_active_host ON live_rooms(host_user_id) WHERE status='live';
CREATE TABLE live_room_participants (
 id varchar(64) PRIMARY KEY,room_id varchar(64) NOT NULL REFERENCES live_rooms(id) ON DELETE CASCADE,user_id varchar(64) NOT NULL REFERENCES users(id),
 role varchar(16) NOT NULL CHECK(role IN ('host','viewer','cohost')),presence_status varchar(16) NOT NULL DEFAULT 'active' CHECK(presence_status IN ('active','left','kicked')),
 is_muted boolean NOT NULL DEFAULT false,is_manager boolean NOT NULL DEFAULT false,joined_at timestamptz NOT NULL DEFAULT now(),left_at timestamptz,
 UNIQUE(room_id,user_id)
);
CREATE TABLE live_start_preflights (
 id varchar(64) PRIMARY KEY,room_id varchar(64) NOT NULL REFERENCES live_rooms(id) ON DELETE CASCADE,host_user_id varchar(64) NOT NULL REFERENCES users(id),
 device_id varchar(128) NOT NULL,app_version varchar(32),camera_permission boolean NOT NULL,microphone_permission boolean NOT NULL,camera_available boolean NOT NULL,microphone_available boolean NOT NULL,
 network_type varchar(16) NOT NULL,latency_ms integer,upload_mbps double precision,provider varchar(32) NOT NULL,result varchar(16) NOT NULL,
 checks jsonb NOT NULL,failure_codes jsonb NOT NULL,warning_codes jsonb NOT NULL,user_confirmed_warnings boolean NOT NULL DEFAULT false,
 ticket_hash varchar(64),expires_at timestamptz,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX live_preflight_room ON live_start_preflights(room_id,created_at DESC);
