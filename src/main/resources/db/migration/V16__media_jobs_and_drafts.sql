ALTER TABLE media_assets
 ADD COLUMN processing_status varchar(16) NOT NULL DEFAULT 'ready' CHECK(processing_status IN ('queued','ready','failed')),
 ADD COLUMN processing_error varchar(64),
 ADD COLUMN processed_at timestamptz;
UPDATE media_assets SET processed_at=created_at;
CREATE TABLE media_processing_jobs (
 media_id varchar(64) PRIMARY KEY REFERENCES media_assets(id) ON DELETE CASCADE,
 payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 33554432),
 input_content_type varchar(80) NOT NULL,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
 available_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX media_processing_available ON media_processing_jobs(available_at,media_id);
CREATE INDEX media_draft_candidates ON media_assets(created_at,id) WHERE status<>'deleted' AND message_id IS NULL;
