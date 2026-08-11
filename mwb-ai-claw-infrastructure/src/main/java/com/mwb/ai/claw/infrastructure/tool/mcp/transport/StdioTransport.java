package com.mwb.ai.claw.infrastructure.tool.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * stdio 传输层实现：通过子进程的标准输入输出与 MCP Server 通信。
 * <p>
 * MCP stdio 协议：每行一条 JSON-RPC 消息（以 \n 分隔）。
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    private final String[] command;
    private final Map<String, String> env;
    private final long timeoutMs;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread readerThread;

    public StdioTransport(String command, java.util.List<String> args, Map<String, String> env) {
        this(command, args, env, DEFAULT_TIMEOUT_MS);
    }

    public StdioTransport(String command, java.util.List<String> args, Map<String, String> env, long timeoutMs) {
        java.util.List<String> cmdList = new java.util.ArrayList<>();
        cmdList.add(command);
        if (args != null) {
            cmdList.addAll(args);
        }
        this.command = cmdList.toArray(new String[0]);
        this.env = env != null ? env : new java.util.HashMap<>();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void connect() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.environment().putAll(env);

        log.info("启动 MCP Server (stdio): {}", String.join(" ", command));
        process = pb.start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 启动读线程，持续读取 stdout
        readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        connected.set(true);
        log.info("MCP Server (stdio) 已连接");
    }

    private void readLoop() {
        try {
            String line;
            while (process.isAlive() && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                responseQueue.offer(line);
            }
        } catch (Exception e) {
            if (connected.get()) {
                log.warn("MCP stdio 读线程异常", e);
            }
        }
    }

    @Override
    public String sendAndWait(String jsonRpcRequest) throws Exception {
        if (!connected.get()) {
            throw new IllegalStateException("传输层未连接");
        }
        synchronized (writer) {
            writer.write(jsonRpcRequest);
            writer.write("\n");
            writer.flush();
        }

        String response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (response == null) {
            throw new java.util.concurrent.TimeoutException("MCP 请求超时（" + timeoutMs + "ms）");
        }
        return response;
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws Exception {
        if (!connected.get()) {
            throw new IllegalStateException("传输层未连接");
        }
        synchronized (writer) {
            writer.write(jsonRpcNotification);
            writer.write("\n");
            writer.flush();
        }
    }

    @Override
    public void disconnect() {
        connected.set(false);
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {}
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {}
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        log.info("MCP Server (stdio) 已断开");
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }
}
