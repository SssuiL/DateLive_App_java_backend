-- Product change: ordinary likes and undo have no paid/daily quota; super-like is retired.
CREATE TABLE explore_actions (
 id varchar(64) PRIMARY KEY,
 actor_user_id varchar(64) NOT NULL REFERENCES users(id),
 target_user_id varchar(64) NOT NULL REFERENCES users(id),
 action_type varchar(16) NOT NULL CHECK(action_type IN ('exposure','like','skip','block','undo')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 undone_at timestamptz,
 CHECK(actor_user_id<>target_user_id)
);
CREATE UNIQUE INDEX explore_active_like ON explore_actions(actor_user_id,target_user_id)
 WHERE action_type='like' AND undone_at IS NULL;
CREATE INDEX explore_actor_time ON explore_actions(actor_user_id,created_at DESC,id DESC);
CREATE INDEX explore_target_likes ON explore_actions(target_user_id,created_at DESC)
 WHERE action_type='like' AND undone_at IS NULL;
CREATE TABLE matches (
 id varchar(64) PRIMARY KEY,
 user_a_id varchar(64) NOT NULL REFERENCES users(id),
 user_b_id varchar(64) NOT NULL REFERENCES users(id),
 conversation_id varchar(64) NOT NULL REFERENCES conversations(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_a_id,user_b_id), CHECK(user_a_id<user_b_id)
);
CREATE INDEX matches_user_b ON matches(user_b_id,created_at DESC);ALTER TABLE media_assets ADD COLUMN portrait_manual_approved boolean NOT NULL DEFAULT false;
