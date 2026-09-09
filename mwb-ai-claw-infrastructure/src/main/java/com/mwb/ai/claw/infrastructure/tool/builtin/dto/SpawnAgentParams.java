package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code spawn_agent} 工具入参（OpenAI function-calling 参数，snake_case 与 JSON Schema 对齐）。
 */
@Data
public class SpawnAgentParams {

    private String task;

    private String name;

    private String instructions;

    private String model;

    private String provider;

    @JsonProperty("max_steps")
    private int maxSteps;

    @JsonProperty("max_tokens")
    private int maxTokens;

    private List<String> tools = new ArrayList<>();

    private boolean memory = true;
}
