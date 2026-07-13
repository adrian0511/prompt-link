package io.github.adrian0511.prompt_link.dto;

/**
 * One message in the conversation. The {@code role} tells the model who is speaking: {@code system}
 * for behaviour instructions, {@code user} for what the person writes, and {@code assistant} for
 * what the model replied in earlier turns.
 *
 * <p>Prefer the factory methods ({@link #system}, {@link #user}, {@link #assistant}) over the
 * constructor, so you cannot get the role name wrong — the API validates it.
 */
public class Message {

    private String role;
    private String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** Behaviour instructions for the model. */
    public static Message system(String content) {
        return new Message("system", content);
    }

    /** A message written by the person. */
    public static Message user(String content) {
        return new Message("user", content);
    }

    /** An earlier reply from the model, used to rebuild the conversation history. */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
