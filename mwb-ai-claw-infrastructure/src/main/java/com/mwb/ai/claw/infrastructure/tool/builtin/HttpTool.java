package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.HttpParams;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 工具：支持 GET 和 POST 请求，获取网页内容或调用 API。
 */
@Component
public class HttpTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpTool.class);

    @Resource
    private ToolSecurity toolSecurity;

    @Override
    public String getName() {
        return "http";
    }

    @Override
    public ToolSpec getSpec() {
        String params = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"url\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"目标 URL（https://...）\"\n"
                + "    },\n"
                + "    \"method\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"HTTP 方法，GET 或 POST\",\n"
                + "      \"enum\": [\"GET\", \"POST\"],\n"
                + "      \"default\": \"GET\"\n"
                + "    },\n"
                + "    \"headers\": {\n"
                + "      \"type\": \"object\",\n"
                + "      \"description\": \"可选的请求头\"\n"
                + "    },\n"
                + "    \"body\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"POST 请求的请求体\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"url\"]\n"
                + "}";
        return new ToolSpec("http", "发送 HTTP 请求获取网页或 API 数据", params);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            HttpParams params = JsonUtils.fromJson(argumentsJson, HttpParams.class);
            String url = params.getUrl();
            String method = params.getMethod();
            if (method == null || method.isEmpty()) {
                method = "GET";
            }
            String body = params.getBody();

            // 安全校验
            toolSecurity.validateHttpUrl(url);

            StringBuilder result = new StringBuilder();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(toolSecurity.getToolTimeoutSeconds() * 1000);
            conn.setReadTimeout(toolSecurity.getToolTimeoutSeconds() * 1000);

            // 设置请求头
            if (params.getHeaders() != null) {
                for (Map.Entry<String, String> entry : params.getHeaders().entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            conn.setRequestProperty("User-Agent", "mwb-ai-claw-agent/1.0");

            // POST 请求
            if ("POST".equalsIgnoreCase(method) && body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = conn.getResponseCode();
            result.append("HTTP ").append(statusCode).append("\n\n");

            // 读取响应
            BufferedReader reader;
            if (statusCode >= 200 && statusCode < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
            reader.close();
            conn.disconnect();

            String output = toolSecurity.truncateOutput(result.toString());
            return ToolResult.success(output);
        } catch (SecurityException e) {
            log.warn("HTTP 安全校验失败: {}", e.getMessage());
            return ToolResult.error("安全拦截: " + e.getMessage());
        } catch (Exception e) {
            log.error("HTTP 请求失败", e);
            return ToolResult.error("HTTP 请求失败: " + e.getMessage());
        }
    }
}
