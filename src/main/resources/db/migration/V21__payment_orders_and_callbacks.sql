CREATE TABLE payment_orders (
 id varchar(64) PRIMARY KEY,merchant_order_no varchar(64) NOT NULL UNIQUE,user_id varchar(64) NOT NULL REFERENCES users(id),
 provider varchar(32) NOT NULL CHECK(provider IN ('mock','wechat_pay')),client_request_id varchar(64) NOT NULL,
 package_code varchar(48) NOT NULL,package_name varchar(80) NOT NULL,amount_fen bigint NOT NULL CHECK(amount_fen>0),coin_amount bigint NOT NULL CHECK(coin_amount>0),
 currency varchar(8) NOT NULL DEFAULT 'CNY',status varchar(24) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','paid','closed')),
 checkout_url varchar(800),checkout_payload jsonb,checkout_claim varchar(64),checkout_until timestamptz,checkout_error varchar(64),
 provider_trade_no varchar(128),billing_transaction_id varchar(64) UNIQUE REFERENCES billing_transactions(id),
 expires_at timestamptz NOT NULL,paid_at timestamptz,closed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,client_request_id),UNIQUE(provider,provider_trade_no),
 CHECK(status<>'paid' OR (provider_trade_no IS NOT NULL AND billing_transaction_id IS NOT NULL AND paid_at IS NOT NULL))
);
CREATE INDEX payment_orders_user_time ON payment_orders(user_id,created_at DESC,id DESC);
CREATE TABLE payment_callback_events (
 id varchar(64) PRIMARY KEY,provider varchar(32) NOT NULL,provider_event_id varchar(128) NOT NULL,event_type varchar(48) NOT NULL,
 merchant_order_no varchar(64) NOT NULL,payment_order_id varchar(64) REFERENCES payment_orders(id),provider_trade_no varchar(128),
 amount_fen bigint NOT NULL,currency varchar(8) NOT NULL,signature_valid boolean NOT NULL,
 processing_status varchar(24) NOT NULL CHECK(processing_status IN ('received','processed','ignored','rejected')),
 error_message varchar(500),payload_hash varchar(64) NOT NULL,occurred_at timestamptz NOT NULL,processed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(provider,provider_event_id)
);
CREATE INDEX payment_callbacks_time ON payment_callback_events(created_at DESC,id DESC);
