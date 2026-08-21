package com.mwb.ai.claw.agent;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.agent.executor.CreateSessionCmdExe;
import com.mwb.ai.claw.agent.executor.SessionDeleteCmdExe;
import com.mwb.ai.claw.agent.executor.SessionListQryExe;
import com.mwb.ai.claw.agent.executor.SessionDuplicateCmdExe;
import com.mwb.ai.claw.agent.executor.SessionQueryExe;
import com.mwb.ai.claw.agent.executor.SessionUpdateCmdExe;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.UpdateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * Agent 应用服务实现：委托给各命令/查询执行器。
 */
@Service
public class AgentServiceImpl implements AgentServiceI {

    @Resource
    private ChatCmdExe chatCmdExe;

    @Resource
    private CreateSessionCmdExe createSessionCmdExe;

    @Resource
    private SessionQueryExe sessionQueryExe;

    @Resource
    private SessionListQryExe sessionListQryExe;

    @Resource
    private SessionDeleteCmdExe sessionDeleteCmdExe;

    @Resource
    private SessionUpdateCmdExe sessionUpdateCmdExe;

    @Resource
    private SessionDuplicateCmdExe sessionDuplicateCmdExe;

    @Override
    public SingleResponse<ChatResponseDTO> chat(ChatCmd cmd) {
        return chatCmdExe.execute(cmd);
    }

    @Override
    public SingleResponse<SessionDTO> createSession(CreateSessionCmd cmd) {
        return createSessionCmdExe.execute(cmd);
    }

    @Override
    public SingleResponse<SessionDTO> getSession(String sessionId) {
        return sessionQueryExe.execute(sessionId);
    }

    @Override
    public SingleResponse<List<SessionDTO>> listSessions() {
        return sessionListQryExe.execute();
    }

    @Override
    public SingleResponse<Void> deleteSession(String sessionId) {
        return sessionDeleteCmdExe.execute(sessionId);
    }

    @Override
    public SingleResponse<SessionDTO> updateSession(String sessionId, UpdateSessionCmd cmd) {
        return sessionUpdateCmdExe.execute(sessionId, cmd);
    }

    @Override
    public SingleResponse<SessionDTO> duplicateSession(String sessionId) {
        return sessionDuplicateCmdExe.execute(sessionId);
    }
}
