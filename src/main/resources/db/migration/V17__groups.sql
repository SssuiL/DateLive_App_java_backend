CREATE TABLE groups (
 id varchar(64) PRIMARY KEY,name varchar(48) NOT NULL,tags jsonb NOT NULL,
 owner_id varchar(64) NOT NULL REFERENCES users(id),link_code varchar(32) NOT NULL UNIQUE,
 join_rule_type varchar(16) NOT NULL CHECK(join_rule_type IN ('manual','open','password','question','invite_only')),
 join_question varchar(240),join_secret_hash varchar(255),max_members integer NOT NULL CHECK(max_members BETWEEN 2 AND 500),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),dissolved_at timestamptz
);
CREATE TABLE group_members (
 group_id varchar(64) NOT NULL REFERENCES groups(id),user_id varchar(64) NOT NULL REFERENCES users(id),
 role varchar(16) NOT NULL CHECK(role IN ('owner','admin','member')),join_source varchar(16) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(group_id,user_id)
);
CREATE UNIQUE INDEX group_one_owner ON group_members(group_id) WHERE role='owner';
CREATE INDEX group_members_user ON group_members(user_id,group_id);
CREATE TABLE group_join_requests (
 id varchar(64) PRIMARY KEY,group_id varchar(64) NOT NULL REFERENCES groups(id),user_id varchar(64) NOT NULL REFERENCES users(id),
 source varchar(16) NOT NULL CHECK(source IN ('search','link_code','invite')),
 status varchar(16) NOT NULL CHECK(status IN ('pending','approved','rejected')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX group_pending_request ON group_join_requests(group_id,user_id) WHERE status='pending';
CREATE INDEX group_requests_time ON group_join_requests(group_id,created_at DESC,id DESC);
ALTER TABLE conversations ADD COLUMN group_id varchar(64) UNIQUE REFERENCES groups(id);
ALTER TABLE conversation_member_states ADD COLUMN active boolean NOT NULL DEFAULT true;
CREATE INDEX conversation_active_members ON conversation_member_states(conversation_id,user_id) WHERE active;
