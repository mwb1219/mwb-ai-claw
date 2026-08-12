package com.mwb.ai.claw.agent.assembler;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.dto.data.MessageDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Session 领域对象 ↔ DTO 转换器
 */
public class SessionAssembler {

    public static SessionDTO toDTO(Session session) {
        if (session == null) {
            return null;
        }
        SessionDTO dto = new SessionDTO();
        dto.setSessionId(session.getSessionId());
        dto.setAgentId(session.getAgentId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus() == null ? null : session.getStatus().name());
        dto.setCreateTime(session.getCreateTime());
        dto.setUpdateTime(session.getUpdateTime());

        List<MessageDTO> messages = new ArrayList<>();
        for (Message msg : session.getMessages()) {
            messages.add(toDTO(msg));
        }
        dto.setMessages(messages);
        return dto;
    }

    public static MessageDTO toDTO(Message msg) {
        MessageDTO dto = new MessageDTO();
        dto.setRole(msg.getRole());
        dto.setContent(msg.getContent());
        dto.setTimestamp(msg.getTimestamp());
        return dto;
    }
}
