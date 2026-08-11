package com.mwb.ai.claw.domain.tool;

import lombok.Data;

/**
 * 工具规格值对象（对应 OpenAI function 的 JSON Schema 定义）
 */
@Data
public class ToolSpec {

    /** 工具名称 */
    private String name;

    /** 工具描述 */
    private String description;

    /** 参数 JSON Schema 字符串 */
    private String parametersJson;

    public ToolSpec() {
    }

    public ToolSpec(String name, String description, String parametersJson) {
        this.name = name;
        this.description = description;
        this.parametersJson = parametersJson;
    }
}
