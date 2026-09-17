ALTER TABLE media_assets
 ADD COLUMN image_derivatives_ready boolean NOT NULL DEFAULT false,
 ADD COLUMN image_derivatives_retry_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX media_image_derivatives_pending ON media_assets(image_derivatives_retry_at,id)
 WHERE media_type='image' AND NOT image_derivatives_ready AND storage_key IS NOT NULL AND status<>'deleted';
