package com.mwb.ai.claw.infrastructure.tool.mcp.transport;

/**
 * MCP 传输层接口：抽象 stdio 和 SSE 两种传输方式。
 * <p>
 * 传输层负责底层数据收发，不关心 JSON-RPC 语义。
 */
public interface McpTransport {

    /**
     * 连接到 MCP Server
     */
    void connect() throws Exception;

    /**
     * 发送一条 JSON-RPC 请求并同步等待响应
     *
     * @param jsonRpcRequest JSON-RPC 2.0 请求字符串
     * @return JSON-RPC 2.0 响应字符串
     */
    String sendAndWait(String jsonRpcRequest) throws Exception;

    /**
     * 发送一条通知（无 id，不等待响应）
     */
    void sendNotification(String jsonRpcNotification) throws Exception;

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 是否已连接
     */
    boolean isConnected();
}
