-- A durable bootstrap marker prevents recreating a default admin after role changes.
CREATE TABLE system_bootstrap (
  name text PRIMARY KEY,
  completed_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_normalized_unique_idx ON app_user(lower(email));
CREATE UNIQUE INDEX refresh_token_one_current_per_session_idx
  ON refresh_token(session_id) WHERE used_at IS NULL AND revoked_at IS NULL;

ALTER TABLE app_user DROP CONSTRAINT app_user_approval_pair_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_approval_check
  CHECK (approved_by_user_id IS NULL OR approved_at IS NOT NULL);

ALTER TABLE admin_audit_log DROP CONSTRAINT admin_audit_log_action_check;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
  'ACCOUNT_APPROVED', 'ACCOUNT_REJECTED', 'ACCOUNT_SUSPENDED', 'ACCOUNT_REACTIVATED',
  'PASSWORD_RESET_ISSUED', 'PASSWORD_RESET_COMPLETED', 'PASSWORD_CHANGED',
  'PLATFORM_ROLE_GRANTED', 'PLATFORM_ROLE_REVOKED', 'BOOTSTRAP_PASSWORD_CHANGED'
));

ALTER TABLE refresh_token ADD CONSTRAINT refresh_token_id_session_unique UNIQUE(id, session_id);
ALTER TABLE refresh_token DROP CONSTRAINT refresh_token_replaced_by_token_id_fkey;
ALTER TABLE refresh_token ADD CONSTRAINT refresh_token_replacement_same_session
  FOREIGN KEY(replaced_by_token_id, session_id) REFERENCES refresh_token(id, session_id);
ALTER TABLE refresh_token ADD CONSTRAINT refresh_token_replacement_consumed
  CHECK (replaced_by_token_id IS NULL OR used_at IS NOT NULL);
CREATE UNIQUE INDEX refresh_token_replacement_unique_idx
  ON refresh_token(replaced_by_token_id) WHERE replaced_by_token_id IS NOT NULL;
CREATE UNIQUE INDEX password_reset_one_pending_per_user_idx
  ON password_reset_token(user_id) WHERE consumed_at IS NULL AND revoked_at IS NULL;
