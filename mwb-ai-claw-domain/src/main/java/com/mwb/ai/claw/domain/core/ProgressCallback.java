package com.mwb.ai.claw.domain.core;

/**
 * 进度回调接口：用于在 ReAct 推理循环执行过程中向外部推送执行进度。
 * <p>
 * 典型用途：SSE 流式推送推理轨迹（Thought / Action / Observation）。
 * 本接口不依赖任何 Spring / Web 框架，仅作为领域层与适配层之间的协作契约。
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * 推送一条执行进度。
     *
     * @param step 进度描述文本，如 "[Thought] ..."、"[Action] ..."、"[Observation] ..."
     */
    void onProgress(String step);
}
