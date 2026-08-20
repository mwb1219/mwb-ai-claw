package com.mwb.ai.claw.example.embed;

import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.runtime.ClawRuntime;

/**
 * 客户端嵌入式示例：演示 {@link ClawRuntime} 在无 Web 容器的 JVM 应用中直接调用 Agent 能力。
 * <p>
 * 运行前通过环境变量或 .env 提供 LLM 密钥：
 * <pre>{@code
 * export DEFAULT_API_KEY=sk-xxx
 * export DEFAULT_MODEL=deepseek-chat
 * export DEFAULT_BASE_URL=https://api.deepseek.com/v1
 * mvn -pl example-embed exec:java -Dexec.mainClass=com.mwb.ai.claw.example.embed.EmbedDemo
 * }</pre>
 */
public class EmbedDemo {

    public static void main(String[] args) {
        // 1. 构建运行时：配置 LLM（不启动任何 Web 容器 / 端口）
        try (ClawRuntime runtime = ClawRuntime.builder()
                .apiKey(System.getenv().getOrDefault("DEFAULT_API_KEY", ""))
                .model(System.getenv().getOrDefault("DEFAULT_MODEL", "deepseek-chat"))
                .baseUrl(System.getenv().getOrDefault("DEFAULT_BASE_URL", "https://api.deepseek.com/v1"))
                .build()) {

            // 2. 首次对话：自动创建会话
            ChatCmd first = new ChatCmd();
            first.setMessage("你好，请用一句话介绍你自己。");
            SingleResponse<ChatResponseDTO> resp = runtime.chat(first);
            if (resp.isSuccess()) {
                String sessionId = resp.getData().getSessionId();
                String reply = resp.getData().getReply();
                System.out.println("== 首次对话 ==");
                System.out.println("sessionId: " + sessionId);
                System.out.println("回复: " + reply);

                // 3. 追问：同一会话继续（上下文连续）
                SingleResponse<ChatResponseDTO> followUp = runtime.chat(sessionId, "你刚才说到了什么？请复述。");
                System.out.println("== 追问 ==");
                System.out.println("回复: " + (followUp.isSuccess() ? followUp.getData().getReply() : followUp.getErrMessage()));
            } else {
                System.out.println("对话失败: " + resp.getErrMessage());
            }
        }
    }
}
