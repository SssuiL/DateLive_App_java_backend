CREATE TABLE posts (
 id varchar(64) PRIMARY KEY, author_id varchar(64) NOT NULL REFERENCES users(id),
 type varchar(16) NOT NULL CHECK(type IN ('text','image','voice','video')),
 text varchar(1000),media_asset_ids jsonb NOT NULL DEFAULT '[]',
 visibility varchar(16) NOT NULL CHECK(visibility IN ('public','friends','private')),
 allow_media_save boolean NOT NULL DEFAULT true,
 text_moderation_status varchar(24) NOT NULL DEFAULT 'approved',
 moderation_status varchar(24) NOT NULL DEFAULT 'approved',
 moderation_reason varchar(240),deleted_at timestamptz,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX posts_author_time ON posts(author_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE TABLE post_likes (
 post_id varchar(64) NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
 user_id varchar(64) NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(post_id,user_id)
);
CREATE TABLE post_comments (
 id varchar(64) PRIMARY KEY,post_id varchar(64) NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
 author_id varchar(64) NOT NULL REFERENCES users(id),
 parent_comment_id varchar(64) REFERENCES post_comments(id) ON DELETE SET NULL,
 root_comment_id varchar(64) REFERENCES post_comments(id) ON DELETE SET NULL,
 reply_to_user_id varchar(64) REFERENCES users(id),content varchar(500),
 media_asset_id varchar(64) UNIQUE REFERENCES media_assets(id) ON DELETE SET NULL,
 media_kind varchar(16) CHECK(media_kind IN ('image','gif','sticker')),
 text_moderation_status varchar(24) NOT NULL DEFAULT 'approved',moderation_status varchar(24) NOT NULL DEFAULT 'approved',
 deleted_at timestamptz,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX post_comments_threads ON post_comments(post_id,root_comment_id,created_at,id);
CREATE TABLE post_comment_likes (
 comment_id varchar(64) NOT NULL REFERENCES post_comments(id) ON DELETE CASCADE,
 user_id varchar(64) NOT NULL REFERENCES users(id),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(comment_id,user_id)
);
CREATE TABLE post_media_reactions (
 post_id varchar(64) NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
 media_asset_id varchar(64) NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
 user_id varchar(64) NOT NULL REFERENCES users(id),emoji varchar(32) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(media_asset_id,user_id)
);
ALTER TABLE media_assets ADD COLUMN post_id varchar(64) REFERENCES posts(id) ON DELETE SET NULL;
ALTER TABLE media_assets DROP CONSTRAINT media_source_context;
ALTER TABLE media_assets ADD CONSTRAINT media_source_context CHECK(
 (source='profile' AND conversation_id IS NULL AND post_id IS NULL) OR
 (source='chat' AND conversation_id IS NOT NULL AND post_id IS NULL) OR
 (source IN ('post','post_comment') AND conversation_id IS NULL));
CREATE INDEX media_post ON media_assets(post_id) WHERE post_id IS NOT NULL;