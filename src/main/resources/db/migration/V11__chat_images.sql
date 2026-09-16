ALTER TABLE media_assets ADD COLUMN source varchar(24) NOT NULL DEFAULT 'profile',
 ADD COLUMN conversation_id varchar(64) REFERENCES conversations(id),
 ADD COLUMN message_id varchar(64) REFERENCES messages(id) ON DELETE SET NULL;
ALTER TABLE media_assets ADD CONSTRAINT media_source_context CHECK(
 (source='profile' AND conversation_id IS NULL) OR (source='chat' AND conversation_id IS NOT NULL));
CREATE INDEX media_conversation ON media_assets(conversation_id) WHERE conversation_id IS NOT NULL;
CREATE UNIQUE INDEX media_message ON media_assets(message_id) WHERE message_id IS NOT NULL;
ALTER TABLE messages DROP CONSTRAINT messages_type_check;
ALTER TABLE messages ADD CONSTRAINT messages_type_check CHECK(type IN ('text','image'));
ALTER TABLE messages ADD COLUMN media_asset_id varchar(64) REFERENCES media_assets(id) ON DELETE SET NULL,
 ADD COLUMN media_kind varchar(16);
ALTER TABLE messages ADD CONSTRAINT messages_media_kind_check CHECK(
 (type='text' AND media_asset_id IS NULL AND media_kind IS NULL) OR (type='image' AND media_kind IN ('image','sticker')));
CREATE UNIQUE INDEX messages_media_asset ON messages(media_asset_id) WHERE media_asset_id IS NOT NULL;
