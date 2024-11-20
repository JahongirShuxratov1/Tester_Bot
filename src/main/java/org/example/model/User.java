package org.example.model;

public class User {
    private Long chatId;
    private String username;
    private boolean isAdmin;
    private String testHistory;

    public User(Long chatId, String username) {
        this.chatId = chatId;
        this.username = username;
        this.isAdmin = false;
        this.testHistory = "";
    }

    // Getters and setters
    public Long getChatId() {
        return chatId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }

    public String getTestHistory() {
        return testHistory;
    }

    public void addTestHistory(String testResult) {
        this.testHistory += testResult + "\n";
    }
} 