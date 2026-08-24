package com.mwb.ai.claw.example.web.rag;

import java.util.Arrays;
import java.util.List;

import com.mwb.ai.claw.domain.rag.access.RagAccessPolicy;

/**
 * 知识库 API 层访问控制示例（T4 生产化能力，{@code agent.rag.access.enabled=true} 时生效）。
 *
 * <p>演示「租户可见性」策略：
 * <ul>
 *   <li>READ（检索 / 列出）：<b>全部放行</b> —— 与框架「知识库全局共享检索」语义一致，
 *       任意租户都可检索全局知识；</li>
 *   <li>WRITE / DELETE：仅允许<b>当前租户私有</b>知识库（id 以 {@code tenantId-} 前缀）或
 *       <b>共享白名单</b>，其它租户的知识库不可被修改 —— 避免越权篡改。</li>
 * </ul>
 * 说明：example-web 使用引导 Key（X-API-Key: sk-admin-bootstrap）登录时租户为 {@code admin}，
 * 因此种子知识库命名为 {@code admin-product-docs} 等；普通注册用户的租户为其用户名，可管理
 * {@code {用户名}-*} 知识库。
 */
public class ExampleRagAccessPolicy implements RagAccessPolicy {

    /** 共享知识库白名单（所有租户均可读写，用于全局运营文档） */
    private static final List<String> SHARED = Arrays.asList("shared-docs");

    @Override
    public boolean canAccess(String tenantId, String userId, String knowledgeBaseId, Action action) {
        // 读取保持全局共享（不改变检索语义）
        if (action == Action.READ) {
            return true;
        }
        // 无鉴权场景（默认空间）视为全部放行
        if (tenantId == null || tenantId.isEmpty()) {
            return true;
        }
        // 引导管理员（bootstrap admin）为超级用户，可管理任意知识库
        if ("admin".equals(tenantId)) {
            return true;
        }
        // 当前租户私有知识库：id 以 {tenantId}- 前缀
        if (knowledgeBaseId != null && knowledgeBaseId.startsWith(tenantId + "-")) {
            return true;
        }
        // 共享白名单
        return SHARED.contains(knowledgeBaseId);
    }
}
