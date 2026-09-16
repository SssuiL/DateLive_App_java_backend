CREATE TABLE account_erasure_records (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL UNIQUE REFERENCES users(id),
 status varchar(24) NOT NULL CHECK(status IN ('pending','running','storage_pending','completed','failed')),
 scheduled_at timestamptz NOT NULL,started_at timestamptz,completed_at timestamptz,
 summary jsonb NOT NULL DEFAULT '{}',error_message varchar(120),
 attempts integer NOT NULL DEFAULT 0,next_retry_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE storage_deletion_jobs (
 id varchar(64) PRIMARY KEY,erasure_record_id varchar(64) NOT NULL REFERENCES account_erasure_records(id),
 storage_key varchar(80),status varchar(24) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','completed')),
 attempts integer NOT NULL DEFAULT 0,last_error_code varchar(64),
 next_retry_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 UNIQUE(erasure_record_id,storage_key)
);
CREATE INDEX account_due ON users(deactivation_due_at) WHERE status='deactivation_pending';
CREATE INDEX storage_deletion_due ON storage_deletion_jobs(next_retry_at) WHERE status='pending';
