package com.example.floatingai;

public class ChatModel {
    private String message;
    private boolean isUser;

    public ChatModel(String message, boolean isUser) {
        this.message = message;
        this.isUser = isUser;
    }

    public String getMessage() { return message; }
    public boolean isUser() { return isUser; }
}
