ALTER TABLE messages ADD COLUMN recalled_at timestamptz,
 ADD COLUMN recalled_by_user_id varchar(64) REFERENCES users(id);
ALTER TABLE message_receipts ADD COLUMN hidden_at timestamptz;
CREATE INDEX receipts_visible ON message_receipts(conversation_id,user_id,message_id) WHERE hidden_at IS NULL;
ALTER TABLE chat_change_outbox DROP CONSTRAINT chat_change_outbox_event_type_check;
ALTER TABLE chat_change_outbox ADD CONSTRAINT chat_change_outbox_event_type_check
 CHECK(event_type IN ('message.created','message.read','message.recalled','message.hidden','conversation.cleared'));
