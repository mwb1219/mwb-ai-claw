package com.mwb.ai.claw.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.agent.observability.RunUsageRecorder;
import com.mwb.ai.claw.dto.SingleResponse;

/**
 * 运行用量查询接口：按日期（yyyy-MM-dd，缺省今天）返回每次 Agent 运行的摘要列表。
 * <p>
 * 数据来自 {@code RunUsageStore}（本地 JSONL 或 JDBC 落库，由
 * {@code agent.observability.run-usage-store} 切换），每条摘要携带 {@code traceId}，
 * 可继续调用 {@code GET /trace/{traceId}} 还原该次执行的全链路步骤明细。
 */
@RestController
@RequestMapping("/runs")
@Profile("web")
public class RunUsageController {

    @Resource
    private ObjectProvider<RunUsageRecorder> recorderProvider;

    /**
     * 查询指定日期的运行记录（时间升序）。未装配记录器或读取失败时返回空列表。
     */
    @GetMapping
    public SingleResponse<List<Map<String, Object>>> runs(
            @RequestParam(value = "date", required = false) String date) {
        RunUsageRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SingleResponse.buildFailure("RUN_USAGE_DISABLED", "运行用量记录未启用");
        }
        return SingleResponse.of(recorder.readRuns(date));
    }
}
