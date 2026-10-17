package dev.repopilot.ai.model;

/**
 * One message in a conversation.
 *
 * <p>{@code content} may contain repository text. It is never logged at INFO or
 * above and never persisted in a prompt log unless {@code repopilot.privacy.log-prompts}
 * is explicitly enabled.
 */
public record ChatMessage(MessageRole role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage(MessageRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(MessageRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(MessageRole.ASSISTANT, content);
    }

    public int characterCount() {
        return content == null ? 0 : content.length();
    }
}
