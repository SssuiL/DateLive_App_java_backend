ALTER TABLE messages DROP CONSTRAINT messages_type_check;
ALTER TABLE messages ADD CONSTRAINT messages_type_check CHECK(type IN ('text','image','voice','video','file'));
ALTER TABLE messages DROP CONSTRAINT messages_media_kind_check;
ALTER TABLE messages ADD CONSTRAINT messages_media_kind_check CHECK(
 (type='text' AND media_asset_id IS NULL AND media_kind IS NULL) OR
 (type='image' AND media_kind IS NOT NULL AND media_kind IN ('image','sticker','gif')) OR
 (type IN ('voice','video','file') AND media_kind IS NULL));
