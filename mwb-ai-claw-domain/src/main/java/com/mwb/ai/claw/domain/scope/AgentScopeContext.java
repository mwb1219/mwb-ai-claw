package com.mwb.ai.claw.domain.scope;

/**
 * 请求级 AgentScope 的 ThreadLocal 上下文。
 * <p>
 * 仅在请求入口（Auth 拦截器 / WS 业务线程 / SSE 参数）设置与清理；
 * 供工具执行器（read_memory / write_memory）、协作工具（invoke_*）、审批服务与
 * 运行用量记录器等读取身份维度。异步任务不依赖 ThreadLocal（显式传参）。
 * <p>
 * 建议读取方优先使用当前 scope 做隔离存储：当请求入口已显式 set 时命中注入值，
 * 未 set（如 shell / 本地联调 / 未认证请求）时回退 {@link AgentScope#defaultScope()}。
 */
public final class AgentScopeContext {

    private static final ThreadLocal<AgentScope> HOLDER = new ThreadLocal<>();

    private AgentScopeContext() {
    }

    /** 设置当前线程 scope（请求入口调用） */
    public static void set(AgentScope scope) {
        HOLDER.set(scope != null ? scope : AgentScope.defaultScope());
    }

    /** 获取当前线程 scope；未设置时返回默认空间（兼容 shell / 本地 / 未认证） */
    public static AgentScope get() {
        AgentScope scope = HOLDER.get();
        return scope != null ? scope : AgentScope.defaultScope();
    }

    /** 清理当前线程 scope（请求入口 finally 调用，防 ThreadLocal 泄露） */
    public static void clear() {
        HOLDER.remove();
    }
}
