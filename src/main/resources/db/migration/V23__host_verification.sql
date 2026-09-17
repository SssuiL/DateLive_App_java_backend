CREATE TABLE live_host_verification_applications (
 id varchar(64) PRIMARY KEY,user_id varchar(64) NOT NULL REFERENCES users(id),
 legal_name_masked varchar(64) NOT NULL,id_number_hash varchar(64) NOT NULL,id_number_masked varchar(32) NOT NULL,
 date_of_birth date NOT NULL,age_at_submission integer NOT NULL CHECK(age_at_submission>=16),guardian_consent boolean NOT NULL,
 agreement_version varchar(32) NOT NULL,agreement_accepted_at timestamptz NOT NULL DEFAULT now(),
 provider varchar(32) NOT NULL DEFAULT 'manual',provider_reference varchar(128),
 status varchar(24) NOT NULL DEFAULT 'pending_review' CHECK(status IN ('pending_review','approved','rejected')),
 rejection_reason varchar(500),reviewer_id varchar(64) REFERENCES admin_users(id),
 submitted_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz
);
CREATE UNIQUE INDEX live_host_one_pending ON live_host_verification_applications(user_id) WHERE status='pending_review';
CREATE INDEX live_host_app_user ON live_host_verification_applications(user_id,submitted_at DESC);
CREATE TABLE live_host_qualifications (
 user_id varchar(64) PRIMARY KEY REFERENCES users(id),identity_status varchar(24) NOT NULL DEFAULT 'unverified' CHECK(identity_status IN ('unverified','pending','verified','rejected')),
 host_permission_status varchar(24) NOT NULL DEFAULT 'not_applied' CHECK(host_permission_status IN ('not_applied','pending_review','enabled','suspended')),
 can_receive_gifts boolean NOT NULL DEFAULT false,current_application_id varchar(64) REFERENCES live_host_verification_applications(id),
 restriction_until timestamptz,restriction_reason varchar(500),last_verified_at timestamptz,updated_at timestamptz NOT NULL DEFAULT now()
);
