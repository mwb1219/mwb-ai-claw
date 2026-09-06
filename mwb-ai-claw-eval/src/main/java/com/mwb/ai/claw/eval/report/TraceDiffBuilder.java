package com.mwb.ai.claw.eval.report;

import java.util.ArrayList;
import java.util.List;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;

/**
 * golden trace 对比工具：对 baseline 与 current 两份 {@link TraceRun} 做「结构 + 关键步骤」归一化 diff。
 * <p>
 * 判定策略：
 * <ul>
 *   <li>步骤内容归一化（去数字→N、压空白、小写）后，以 {@code type:content} 作为签名做 LCS 最长公共子序列对齐；</li>
 *   <li>removed==0 且 added==0 → {@link TraceDiffStatus#UNCHANGED}；</li>
 *   <li>缺失关键 action 或 removed&gt;0 → {@link TraceDiffStatus#REGRESSED}；</li>
 *   <li>仅新增步骤（removed==0 且 added&gt;0）→ {@link TraceDiffStatus#IMPROVED}；</li>
 *   <li>基线存在但为空而当前非空 → IMPROVED；基线非空而当前为空 → REGRESSED。</li>
 * </ul>
 * 纯函数、零依赖，仅统计 LCS 差异，不做全量矩阵瓶颈（步数规模小）。
 */
public final class TraceDiffBuilder {

    private TraceDiffBuilder() {
    }

    /** 对比两份 trace，返回状态与说明。 */
    public static TraceDiff compare(TraceRun baseline, TraceRun current) {
        TraceDiff diff = new TraceDiff();
        List<String> base = signatures(baseline);
        List<String> cur = signatures(current);

        if (base.isEmpty() && cur.isEmpty()) {
            diff.setStatus(TraceDiffStatus.UNCHANGED);
            diff.setDetails("两侧均无步骤");
            return diff;
        }
        if (base.isEmpty()) {
            diff.setStatus(TraceDiffStatus.IMPROVED);
            diff.setDetails("基线无步骤，当前有 " + cur.size() + " 步");
            return diff;
        }
        if (cur.isEmpty()) {
            diff.setStatus(TraceDiffStatus.REGRESSED);
            diff.setDetails("当前无步骤，基线有 " + base.size() + " 步");
            return diff;
        }

        LcsAlign align = lcs(base, cur);
        List<Integer> removedIdx = align.removedBaseline; // 基线中被移除的索引
        List<Integer> addedIdx = align.addedCurrent;       // 当前中新增的索引

        boolean hasCriticalRemoved = removedIdx.stream()
                .anyMatch(i -> typeOf(baseline.getSteps().get(i)).equals("action"));

        TraceDiffStatus status;
        if (removedIdx.isEmpty() && addedIdx.isEmpty()) {
            status = TraceDiffStatus.UNCHANGED;
        } else if (hasCriticalRemoved || !removedIdx.isEmpty()) {
            status = TraceDiffStatus.REGRESSED;
        } else {
            status = TraceDiffStatus.IMPROVED;
        }

        diff.setStatus(status);
        diff.setDetails(buildDetails(baseline.getSteps(), current.getSteps(), removedIdx, addedIdx, hasCriticalRemoved));
        return diff;
    }

    // ==================== 内部实现 ====================

    /** 步骤签名列表：type:归一化内容。 */
    private static List<String> signatures(TraceRun run) {
        List<String> out = new ArrayList<>();
        if (run == null || run.getSteps() == null) {
            return out;
        }
        for (TraceStep s : run.getSteps()) {
            out.add(typeOf(s) + ":" + normalizeContent(s.getContent()));
        }
        return out;
    }

    private static String typeOf(TraceStep s) {
        String t = s.getType();
        return t == null ? "step" : t.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 归一化内容：去数字→N、压空白、小写、去首尾空白；保留前缀标签以便类型锚定。 */
    private static String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\d+", "N")
                .replaceAll("\\s+", " ");
    }

    /** 基于签名的 LCS 对齐，返回基线中被移除、当前中新增的索引（均按原序列顺序）。 */
    private static LcsAlign lcs(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Integer> removed = new ArrayList<>();
        List<Integer> added = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                removed.add(i++);
            } else {
                added.add(j++);
            }
        }
        while (i < n) {
            removed.add(i++);
        }
        while (j < m) {
            added.add(j++);
        }
        return new LcsAlign(removed, added);
    }

    /** 拼接可读说明：两侧步数 + 缺失/新增步骤摘要（各截前 3 条，超长省略）。 */
    private static String buildDetails(List<TraceStep> baseline, List<TraceStep> current,
                                       List<Integer> removedIdx, List<Integer> addedIdx,
                                       boolean criticalRemoved) {
        StringBuilder sb = new StringBuilder();
        sb.append("baseline ").append(baseline == null ? 0 : baseline.size())
                .append(" 步 / current ").append(current == null ? 0 : current.size()).append(" 步");
        if (criticalRemoved) {
            sb.append("；关键 action 缺失");
        }
        if (!removedIdx.isEmpty()) {
            sb.append("；缺失: ").append(brief(baseline, removedIdx));
        }
        if (!addedIdx.isEmpty()) {
            sb.append("；新增: ").append(brief(current, addedIdx));
        }
        return sb.toString();
    }

    private static String brief(List<TraceStep> steps, List<Integer> idx) {
        int limit = 3;
        List<String> items = new ArrayList<>(Math.min(idx.size(), limit));
        for (int k = 0; k < idx.size() && k < limit; k++) {
            TraceStep s = steps.get(idx.get(k));
            String c = s.getContent() == null ? "" : s.getContent();
            items.add(typeOf(s) + ":" + truncate(c));
        }
        String joined = String.join(" | ", items);
        return idx.size() > limit ? joined + " …(+" + (idx.size() - limit) + ")" : joined;
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static final class LcsAlign {
        final List<Integer> removedBaseline;
        final List<Integer> addedCurrent;

        LcsAlign(List<Integer> removedBaseline, List<Integer> addedCurrent) {
            this.removedBaseline = removedBaseline;
            this.addedCurrent = addedCurrent;
        }
    }
}