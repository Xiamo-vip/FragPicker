ALTER TABLE chat_turns ADD KEY idx_chat_turn_session_page (session_id, user_id, id);
