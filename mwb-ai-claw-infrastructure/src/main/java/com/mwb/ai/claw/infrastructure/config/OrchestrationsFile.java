package com.mwb.ai.claw.infrastructure.config;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import lombok.Data;

import java.util.List;

/**
 * orchestrations.json 顶层结构（编排注册表）。
 */
@Data
public class OrchestrationsFile {

    /** 编排定义列表 */
    private List<OrchestrationDefinition> orchestrations;
}
