CREATE TABLE admin_users (
 id varchar(64) PRIMARY KEY, username varchar(64) NOT NULL UNIQUE,
 display_name varchar(64) NOT NULL, password_hash varchar(255) NOT NULL,
 role varchar(32) NOT NULL CHECK(role IN ('owner','operator','auditor')),
 status varchar(24) NOT NULL DEFAULT 'active' CHECK(status IN ('active','disabled')),
 token_version bigint NOT NULL DEFAULT 0, last_login_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE admin_operation_logs (
 id varchar(64) PRIMARY KEY, admin_user_id varchar(64) REFERENCES admin_users(id),
 action varchar(64) NOT NULL,target_type varchar(32),target_id varchar(64),
 details jsonb NOT NULL DEFAULT '{}', request_id varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_logs_time ON admin_operation_logs(created_at DESC,id DESC);
