-- Messaging team members (named team_member: the Organization service owns team_members). Run in apargo_wa_messaging.
USE apargo_wa_messaging;
CREATE TABLE IF NOT EXISTS team_member (
    team_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    role ENUM('MEMBER','LEAD') NOT NULL DEFAULT 'MEMBER',
    active_conversations SMALLINT NOT NULL DEFAULT 0, is_available TINYINT(1) NOT NULL DEFAULT 1,
    added_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id, user_id), KEY idx_user (user_id), KEY idx_routing (team_id, is_available, active_conversations),
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE) ENGINE=InnoDB;
