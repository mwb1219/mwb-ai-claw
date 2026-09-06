package com.mwb.ai.claw.eval.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * 数据集条目摘要（{@code eval ls} 展示用）：标识、展示名、所属文件与用例数。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetInfo {

    /** 数据集 id（task.id） */
    private String taskId;

    /** 数据集显示名（task.name，可为空） */
    private String name;

    /** 数据集源文件路径 */
    private String file;

    /** 用例数 */
    private int caseCount;

    /** 是否成功加载（解析/校验失败时仍列出，标明原因） */
    private boolean loaded;

    /** 加载失败原因（loaded=false 时） */
    private String error;

    public DatasetInfo() {
    }

    public DatasetInfo(String taskId, String name, String file, int caseCount) {
        this.taskId = taskId;
        this.name = name;
        this.file = file;
        this.caseCount = caseCount;
        this.loaded = true;
    }

    public static DatasetInfo failed(String file, String reason) {
        DatasetInfo info = new DatasetInfo();
        info.taskId = "(读取失败)";
        info.file = file;
        info.loaded = false;
        info.error = reason;
        return info;
    }
}