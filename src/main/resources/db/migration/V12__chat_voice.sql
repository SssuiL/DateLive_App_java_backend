ALTER TABLE media_assets ADD COLUMN duration_ms integer NOT NULL DEFAULT 0 CHECK(duration_ms BETWEEN 0 AND 60500);
ALTER TABLE messages DROP CONSTRAINT messages_type_check;
ALTER TABLE messages ADD CONSTRAINT messages_type_check CHECK(type IN ('text','image','voice'));
ALTER TABLE messages DROP CONSTRAINT messages_media_kind_check;
ALTER TABLE messages ADD CONSTRAINT messages_media_kind_check CHECK(
 (type='text' AND media_asset_id IS NULL AND media_kind IS NULL) OR
 (type='image' AND media_kind IS NOT NULL AND media_kind IN ('image','sticker')) OR
 (type='voice' AND media_kind IS NULL));
ALTER TABLE messages ADD COLUMN duration_seconds integer NOT NULL DEFAULT 0 CHECK(duration_seconds BETWEEN 0 AND 60);
