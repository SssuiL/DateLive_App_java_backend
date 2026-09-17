CREATE TABLE gift_risk_confirmations (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL REFERENCES users(id),room_id varchar(64) NOT NULL REFERENCES live_rooms(id) ON DELETE CASCADE,
 gift_code varchar(32) NOT NULL,quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),total_coins bigint NOT NULL CHECK(total_coins>0),
 expires_at timestamptz NOT NULL,used_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE live_gift_records (
 id varchar(64) PRIMARY KEY,room_id varchar(64) NOT NULL REFERENCES live_rooms(id),sender_id varchar(64) NOT NULL REFERENCES users(id),host_user_id varchar(64) NOT NULL REFERENCES users(id),
 gift_code varchar(32) NOT NULL,gift_name varchar(64) NOT NULL,gift_icon_url varchar(500),gift_animation_url varchar(500),gift_renderer_type varchar(16) NOT NULL,
 gift_animation_checksum varchar(64),gift_animation_file_size bigint,gift_min_client_version varchar(32),gift_presentation_tier varchar(16) NOT NULL,gift_animation_duration_ms integer NOT NULL,
 combo_id varchar(64) NOT NULL,combo_count integer NOT NULL CHECK(combo_count>0),combo_expires_at timestamptz NOT NULL,is_high_value boolean NOT NULL,
 unit_price bigint NOT NULL CHECK(unit_price>0),quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),total_coins bigint NOT NULL CHECK(total_coins=unit_price*quantity),
 host_income_coins bigint NOT NULL CHECK(host_income_coins>=0 AND host_income_coins<=total_coins),income_status varchar(16) NOT NULL DEFAULT 'pending' CHECK(income_status IN ('pending','available','processing','settled','refunded')),
 client_request_id varchar(64) NOT NULL,settlement_id varchar(64),billing_transaction_id varchar(64) UNIQUE REFERENCES billing_transactions(id),refund_transaction_id varchar(64) UNIQUE REFERENCES billing_transactions(id),
 refunded_at timestamptz,refund_reason varchar(500),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(sender_id,client_request_id)
);
CREATE INDEX live_gift_room_time ON live_gift_records(room_id,created_at DESC,id DESC);
CREATE INDEX live_gift_host_status ON live_gift_records(host_user_id,income_status,created_at);
CREATE INDEX live_gift_combo ON live_gift_records(sender_id,combo_id,created_at DESC);
ALTER TABLE gift_risk_events ADD CONSTRAINT gift_risk_record_fk FOREIGN KEY(gift_record_id) REFERENCES live_gift_records(id);
ALTER TABLE coin_accounts ADD CONSTRAINT host_payable_nonnegative CHECK(account_type<>'host_payable' OR (owner_type='user' AND balance>=0));
