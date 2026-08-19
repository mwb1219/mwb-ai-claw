package com.mwb.ai.claw.agent.executor;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.agent.assembler.SessionAssembler;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话列表查询执行器
 */
@Component
public class SessionListQryExe {

    @Resource
    private MemoryGateway memoryGateway;

    public SingleResponse<List<SessionDTO>> execute() {
        List<Session> sessions = memoryGateway.listSessions();
        List<SessionDTO> dtos = new ArrayList<>();
        for (Session session : sessions) {
            dtos.add(SessionAssembler.toDTO(session));
        }
        return SingleResponse.of(dtos);
    }
}
