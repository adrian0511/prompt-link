package io.github.adrian0511.prompt_link.dto;

public class Message {
    private String role;
    private String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.content = content;
        this.role = role;
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
