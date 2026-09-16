ALTER TABLE media_assets ADD COLUMN storage_key varchar(80),
 ADD COLUMN original_filename varchar(255), ADD COLUMN content_type varchar(80),
 ADD COLUMN file_size bigint, ADD COLUMN sha256 varchar(64),
 ADD COLUMN width integer, ADD COLUMN height integer,
 ADD COLUMN reviewed_by varchar(64), ADD COLUMN reviewed_at timestamptz,
 ADD COLUMN moderation_reason varchar(240);
CREATE UNIQUE INDEX media_storage_key ON media_assets(storage_key) WHERE storage_key IS NOT NULL;
CREATE TABLE media_reviews (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 media_id varchar(64) NOT NULL REFERENCES media_assets(id),
 reviewer_id varchar(64) NOT NULL, action varchar(24) NOT NULL,
 reason varchar(240), created_at timestamptz NOT NULL DEFAULT now()
);
