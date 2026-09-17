CREATE TABLE wallets (
 user_id varchar(64) PRIMARY KEY REFERENCES users(id), balance bigint NOT NULL DEFAULT 0 CHECK(balance>=0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE coin_accounts (
 id varchar(64) PRIMARY KEY, owner_type varchar(24) NOT NULL CHECK(owner_type IN ('user','platform')),
 owner_id varchar(64) NOT NULL, user_id varchar(64) REFERENCES users(id), account_type varchar(48) NOT NULL,
 balance bigint NOT NULL DEFAULT 0, status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','frozen')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(owner_type,owner_id,account_type),
 CHECK((owner_type='user' AND user_id=owner_id AND user_id IS NOT NULL) OR (owner_type='platform' AND user_id IS NULL)),
 CHECK(account_type<>'user_spendable' OR (owner_type='user' AND balance>=0))
);
CREATE TABLE billing_transactions (
 id varchar(64) PRIMARY KEY, idempotency_key varchar(160) NOT NULL UNIQUE,
 transaction_type varchar(48) NOT NULL, status varchar(24) NOT NULL DEFAULT 'posted' CHECK(status='posted'),
 initiated_by_user_id varchar(64) REFERENCES users(id), reference_type varchar(48),reference_id varchar(64),
 amount_coins bigint NOT NULL CHECK(amount_coins>0),payload_hash varchar(64) NOT NULL,
 reversal_of_transaction_id varchar(64) UNIQUE REFERENCES billing_transactions(id),metadata jsonb NOT NULL DEFAULT '{}',
 posted_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE coin_ledger_entries (
 id varchar(64) PRIMARY KEY,transaction_id varchar(64) NOT NULL REFERENCES billing_transactions(id),
 account_id varchar(64) NOT NULL REFERENCES coin_accounts(id),entry_role varchar(48) NOT NULL,
 amount bigint NOT NULL CHECK(amount<>0),balance_after bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ledger_account_time ON coin_ledger_entries(account_id,created_at DESC,id DESC);
CREATE INDEX ledger_transaction ON coin_ledger_entries(transaction_id);
CREATE INDEX billing_time ON billing_transactions(created_at DESC,id DESC);
CREATE TABLE wallet_transactions (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL REFERENCES users(id),type varchar(32) NOT NULL,
 amount bigint NOT NULL,balance_after bigint NOT NULL CHECK(balance_after>=0),
 reference_type varchar(48),reference_id varchar(64),remark varchar(240),billing_transaction_id varchar(64) NOT NULL REFERENCES billing_transactions(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX wallet_transactions_user ON wallet_transactions(user_id,created_at DESC,id DESC);
-- Deferred checks prevent committing half a transfer even if a future caller bypasses the service.
CREATE FUNCTION verify_billing_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target varchar(64); n bigint; total numeric;
BEGIN
 IF TG_TABLE_NAME='billing_transactions' THEN target := NEW.id; ELSE target := NEW.transaction_id; END IF;
 SELECT count(*),coalesce(sum(amount),0) INTO n,total FROM coin_ledger_entries WHERE transaction_id=target;
 IF n<2 OR total<>0 THEN RAISE EXCEPTION 'Unbalanced billing transaction' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER billing_balanced AFTER INSERT ON billing_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_billing_balance();
CREATE CONSTRAINT TRIGGER entries_balanced AFTER INSERT ON coin_ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_billing_balance();
CREATE FUNCTION immutable_billing_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Billing history is immutable; post a reversal' USING ERRCODE='23514'; END $$;
CREATE TRIGGER billing_immutable BEFORE UPDATE OR DELETE ON billing_transactions FOR EACH ROW EXECUTE FUNCTION immutable_billing_record();
CREATE TRIGGER entries_immutable BEFORE UPDATE OR DELETE ON coin_ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_billing_record();
CREATE TRIGGER wallet_history_immutable BEFORE UPDATE OR DELETE ON wallet_transactions FOR EACH ROW EXECUTE FUNCTION immutable_billing_record();
