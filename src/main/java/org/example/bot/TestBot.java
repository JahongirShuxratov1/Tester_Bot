package org.example.bot;

import org.example.dao.DatabaseDao;
import org.example.model.Test;
import org.example.model.User;
import org.example.service.TestService;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

public class TestBot extends TelegramLongPollingBot {
    private final TestService testService;
    private final DatabaseDao databaseDao;
    private final Map<Long, User> users;
    private final Map<Long, String> userStates;
    private final Map<Long, String> currentTestId = new HashMap<>();
    private final Map<Long, LocalDateTime> testStartTimes = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<Long, String> temporaryAnswers = new HashMap<>();
    private final Map<Long, TestCreationState> testCreationStates = new HashMap<>();
    private final Map<Long, Integer> lastMessageId = new HashMap<>();

    public TestBot(DatabaseDao databaseDao) {
        try {
            this.databaseDao = databaseDao;
            this.testService = new TestService(databaseDao);
            this.users = new HashMap<>();
            this.userStates = new HashMap<>();
        } catch (Exception e) {
            System.err.println("Failed to initialize bot: " + e.getMessage());
            throw new RuntimeException("Bot initialization failed", e);
        }
    }

    @Override
    public String getBotUsername() {
        return "LexusGetInfoBot"; // Replace with your bot's username
    }

    @Override
    public String getBotToken() {
        return "7896721460:AAEzIqEuUFGrqWBHCzogc5OQ-SDctsUzVTU"; // Replace with your bot's token
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            if (update.getMessage().hasDocument()) {
                handleDocument(update);
            } else {
                handleMessage(update);
            }
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
        }
    }

    private void handleMessage(Update update) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();
        
        // Store user information
        updateUserInfo(update.getMessage().getFrom(), chatId);
        System.out.println(update.getMessage().getChatId());

        if (text.equals("/start")) {
            showMainMenu(chatId);
        } else if (userStates.get(chatId) != null) {
            handleStateMessage(chatId, text, update);
        }
    }

    private void updateUserInfo(org.telegram.telegrambots.meta.api.objects.User telegramUser, Long chatId) {
        String username = getUsernameFromTelegramUser(telegramUser);
        User user = new User(chatId, username);
        users.put(chatId, user);
        
        // Update in database
        try {
            databaseDao.saveUserUsername(chatId, username);
        } catch (Exception e) {
            System.err.println("Failed to update user in database: " + e.getMessage());
        }
    }

    private String getUserIdentifier(Long chatId) {
        User user = users.get(chatId);
        if (user == null) {
            // Try to get from database
            try {
                // Get username from Telegram user if available
                if (databaseDao.hasUser(chatId)) {
                    String storedUsername = databaseDao.getUserUsername(chatId);
                    user = new User(chatId, storedUsername);
                    users.put(chatId, user);
                    return storedUsername;
                }
            } catch (Exception e) {
                System.err.println("Error retrieving user from database: " + e.getMessage());
            }
            return "User_" + chatId;
        }
        return user.getUsername();
    }

    private void handleDocument(Update update) {
        Long chatId = update.getMessage().getChatId();
        
        if ("WAITING_PDF".equals(userStates.get(chatId))) {
            Document document = update.getMessage().getDocument();
            
            if (document.getMimeType().equals("application/pdf")) {
                try {
                    // Create unique directory for each test
                    String testDir = "tests/" + System.currentTimeMillis();
                    Files.createDirectories(Paths.get(testDir));
                    
                    TestCreationState state = new TestCreationState();
                    state.pdfPath = testDir + "/test.pdf";
                    testCreationStates.put(chatId, state);
                    
                    // Add cleanup of old files
                    cleanupOldTestFiles();
                    
                    // Download file
                    GetFile getFile = new GetFile();
                    getFile.setFileId(document.getFileId());
                    File file = execute(getFile);
                    downloadFile(file, new java.io.File(state.pdfPath));
                    
                    System.out.println("PDF file downloaded successfully");
                    
                    userStates.put(chatId, "WAITING_TEST_ANSWERS");
                    sendMessage(chatId, "📄 PDF received! Now please send the test answers in format: 1a2b3c");
                    
                } catch (Exception e) {
                    handleDocumentError(chatId, e);
                }
            } else {
                sendMessage(chatId, "❌ Please send a PDF file.");
            }
        }
    }

    private void cleanupOldTestFiles() {
        try {
            Path testsDir = Paths.get("tests");
            if (Files.exists(testsDir)) {
                Files.walk(testsDir)
                    .filter(path -> Files.isRegularFile(path))
                    .filter(path -> path.toString().endsWith(".pdf"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < 
                                   System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete old file: " + path);
                        }
                    });
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up old files: " + e.getMessage());
        }
    }

    private void downloadAndSaveFile(Document document, Long chatId) {
        try {
            GetFile getFile = new GetFile();
            getFile.setFileId(document.getFileId());
            File file = execute(getFile);
            
            String testId = "test_" + System.currentTimeMillis();
            String localPath = "tests/" + testId + ".pdf";
            
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get("tests"));
            
            // Download file
            downloadFile(file, new java.io.File(localPath));
            
            currentTestId.put(chatId, testId);
            userStates.put(chatId, "WAITING_TEST_ANSWERS");
            sendMessage(chatId, "PDF received! Now please send the test answers in format: 1a2b3c");
            
        } catch (TelegramApiException | IOException e) {
            sendMessage(chatId, "Error processing file. Please try again.");
            e.printStackTrace();
        }
    }

    private void showMainMenu(Long chatId) {
        // Clear all states when returning to main menu
        cleanupAllStates(chatId);
        
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        if (databaseDao.isAdmin(chatId)) {
            keyboard.add(Collections.singletonList(
                    createInlineButton("👨‍💼 Admin Panel", "admin_panel")));
        } else {
            keyboard.add(Collections.singletonList(
                    createInlineButton("👨‍🎓 Student Panel", "student_panel")));
        }

        markup.setKeyboard(keyboard);
        String text = "👋 Welcome! Select your role:";
        
        Integer messageId = lastMessageId.get(chatId);
        if (messageId != null) {
            try {
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(chatId.toString());
                editMessage.setMessageId(messageId);
                editMessage.setText(text);
                editMessage.setReplyMarkup(markup);
                execute(editMessage);
                return;
            } catch (TelegramApiException e) {
                if (!e.getMessage().contains("message is not modified")) {
                    System.err.println("Failed to edit message: " + e.getMessage());
                }
            }
        }

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setReplyMarkup(markup);

        try {
            Message sentMessage = execute(message);
            lastMessageId.put(chatId, sentMessage.getMessageId());
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }

    private InlineKeyboardButton createInlineButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();

        System.out.println("Received callback: " + callbackData);

        // Clear states when switching panels
        if (callbackData.equals("admin_panel") || callbackData.equals("student_panel")) {
            cleanupAllStates(chatId);
        }

        if (callbackData.startsWith("back_")) {
            String destination = callbackData.substring(5);
            switch (destination) {
                case "main":
                    showMainMenu(chatId);
                    break;
                case "admin":
                    showAdminPanel(chatId);
                    break;
                case "student":
                    showStudentPanel(chatId);
                    break;
            }
            return;
        }

        if (callbackData.startsWith("time_")) {
            String timeStr = callbackData.substring(5);
            int timeLimit = Integer.parseInt(timeStr);
            handleTimeLimitSelection(chatId, timeLimit);
            return;
        }

        switch (callbackData) {
            case "admin_panel":
                if (databaseDao.isAdmin(chatId)) {
                    showAdminPanel(chatId);
                } else {
                    sendMessage(chatId, "You don't have admin privileges.");
                    showMainMenu(chatId);
                }
                break;
            case "student_panel":
                showStudentPanel(chatId);
                break;
            case "view_rankings":
                showTestList(chatId, true);
                break;
            case "add_test":
                handleAddTest(chatId);
                break;
            case "edit_test":
                showTestListForEdit(chatId);
                break;
            case "delete_test":
                showTestList(chatId, false);
                break;
            case "participate":
                showAvailableTests(chatId);
                break;
            case "my_history":
                showUserHistory(chatId);
                break;
            case "manage_admins":
                if (databaseDao.isAdmin(chatId)) {
                    showAdminManagement(chatId);
                }
                break;
            case "broadcast_message":
                if (databaseDao.isAdmin(chatId)) {
                    userStates.put(chatId, "WAITING_FOR_BROADCAST");
                    sendMessage(chatId, """
                        Please send your broadcast:
                        
                        You can send:
                        - Text message
                        - Photo (with optional caption)
                        - PDF document (with optional caption)
                        
                        Your message will be sent to all users.""");
                }
                break;
            default:
                if (callbackData.startsWith("delete_") || 
                    callbackData.startsWith("edit_") || 
                    callbackData.startsWith("take_") || 
                    callbackData.startsWith("rankings_") ||
                    callbackData.startsWith("test_")) {
                    handleTestSelection(chatId, callbackData);
                }
        }
    }

    private void handleStateMessage(Long chatId, String text, Update update) {
        String currentState = userStates.get(chatId);
        
        switch (currentState) {
            case "WAITING_NEW_ADMIN_ID":
                handleNewAdminId(chatId, text);
                break;
            case "WAITING_PDF":
                // This will be handled in handleDocument method
                break;
            case "WAITING_TEST_ANSWERS":
                handleTestAnswers(chatId, text);
                break;
            case "WAITING_EDIT_ANSWERS":
                handleEditAnswers(chatId, text);
                break;
            case "PARTICIPATING_TEST":
                handleTestSubmission(chatId, text, update);
                break;
            case "WAITING_FOR_BROADCAST":
                if (update.getMessage().hasDocument() || update.getMessage().hasPhoto()) {
                    handleBroadcastMedia(chatId, update.getMessage());
                } else {
                    handleBroadcastMessage(chatId, text);
                }
                break;
        }
    }
    private void handleBroadcastMessage(Long chatId, String text) {
        if (!databaseDao.isAdmin(chatId)) {
            sendMessage(chatId, "You don't have permission to broadcast messages.");
            return;
        }

        Collection<Long> allUserChatIds = databaseDao.getAllUserChatIds();
        int successCount = 0;
        List<Long> failedRecipients = new ArrayList<>(); // Track failed recipients

        for (Long userChatId : allUserChatIds) {
            try {
                sendMessage(userChatId, "📢 Broadcast from admin:\n\n" + text);
                successCount++;
            } catch (Exception e) {
                failedRecipients.add(userChatId);
                System.err.println("Failed to send broadcast to " + userChatId + ": " + e.getMessage());
            }
        }

        databaseDao.saveBroadcastMessage(chatId, text);

        // More detailed summary including failed recipients if any
        StringBuilder summary = new StringBuilder(String.format("""
            📊 Broadcast Summary:
            ✅ Successfully delivered: %d
            ❌ Failed deliveries: %d
            📈 Total recipients: %d
            """, 
            successCount, failedRecipients.size(), allUserChatIds.size()));

        if (!failedRecipients.isEmpty()) {
            summary.append("\nFailed deliveries to these chat IDs:\n");
            failedRecipients.forEach(id -> summary.append(id).append("\n"));
        }

        sendMessage(chatId, summary.toString());
        showAdminPanel(chatId);
    }

    private void handleBroadcastMedia(Long chatId, Message message) {
        try {
            Collection<Long> userChatIds = databaseDao.getAllUserChatIds();
            int successCount = 0;
            List<Long> failedRecipients = new ArrayList<>();
            String caption = message.getCaption() != null ? message.getCaption() : "";
            String mediaType = "";

            if (message.hasDocument() && message.getDocument().getMimeType().equals("application/pdf")) {
                mediaType = "PDF Document";
                String fileId = message.getDocument().getFileId();
                for (Long userChatId : userChatIds) {
                    try {
                        SendDocument sendDocument = new SendDocument();
                        sendDocument.setChatId(userChatId);
                        sendDocument.setDocument(new InputFile(fileId));
                        sendDocument.setCaption("📢 Document from Admin:\n\n" + caption);
                        execute(sendDocument);
                        successCount++;
                    } catch (Exception e) {
                        failedRecipients.add(userChatId);
                        System.err.println("Failed to send broadcast document to " + userChatId + ": " + e.getMessage());
                    }
                }
            } else if (message.hasPhoto()) {
                mediaType = "Photo";
                List<PhotoSize> photos = message.getPhoto();
                String fileId = photos.get(photos.size() - 1).getFileId();
                
                for (Long userChatId : userChatIds) {
                    try {
                        SendPhoto sendPhoto = new SendPhoto();
                        sendPhoto.setChatId(userChatId);
                        sendPhoto.setPhoto(new InputFile(fileId));
                        sendPhoto.setCaption("📢 Photo from Admin:\n\n" + caption);
                        execute(sendPhoto);
                        successCount++;
                    } catch (Exception e) {
                        failedRecipients.add(userChatId);
                        System.err.println("Failed to send broadcast photo to " + userChatId + ": " + e.getMessage());
                    }
                }
            }

            databaseDao.saveBroadcastMessage(chatId, 
                message.hasDocument() ? "📎 Document: " + caption : "🖼 Photo: " + caption);

            // Enhanced feedback with media type and failed recipients
            StringBuilder feedback = new StringBuilder(String.format("""
                📊 %s Broadcast Summary:
                ✅ Successfully delivered: %d
                ❌ Failed deliveries: %d
                📈 Total recipients: %d
                """, 
                mediaType, successCount, failedRecipients.size(), userChatIds.size()));

            if (!failedRecipients.isEmpty()) {
                feedback.append("\nFailed deliveries to these chat IDs:\n");
                failedRecipients.forEach(id -> feedback.append(id).append("\n"));
                feedback.append("\nThese users may have blocked the bot or deleted their accounts.");
            }

            sendMessage(chatId, feedback.toString());

        } catch (Exception e) {
            System.err.println("Error broadcasting media: " + e.getMessage());
            sendMessage(chatId, String.format("""
                ❌ Error sending broadcast media:
                Error type: %s
                Message: %s
                
                Please try again or contact support.""",
                e.getClass().getSimpleName(), e.getMessage()));
        } finally {
            userStates.remove(chatId);
            showAdminPanel(chatId);
        }
    }

    private void showAdminPanel(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        keyboard.add(Collections.singletonList(
                createInlineButton("📊 View Rankings", "view_rankings")));
        keyboard.add(Collections.singletonList(
                createInlineButton("➕ Add Test", "add_test")));
        keyboard.add(Collections.singletonList(
                createInlineButton("✏️ Edit Test", "edit_test")));
        keyboard.add(Collections.singletonList(
                createInlineButton("🗑️ Delete Test", "delete_test")));
        keyboard.add(Collections.singletonList(
                createInlineButton("👥 Manage Admins", "manage_admins")));
        keyboard.add(Collections.singletonList(
                createInlineButton("📢 Broadcast Message", "broadcast_message")));
            
        addBackButton(keyboard, "main");

        markup.setKeyboard(keyboard);
        sendOrEditMessage(chatId, "⚙️ Admin Panel - Select an action:", markup);
    }

    private void showStudentPanel(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        keyboard.add(Collections.singletonList(
                createInlineButton("📝 Take Test", "participate")));
        keyboard.add(Collections.singletonList(
                createInlineButton("📚 My History", "my_history")));
            
        addBackButton(keyboard, "main");

        markup.setKeyboard(keyboard);
        sendOrEditMessage(chatId, "📋 Student Panel - Select an action:", markup);
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Helper methods to be implemented
    private void showTestList(Long chatId, boolean forRankings) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        Collection<Test> tests = testService.getAllTests();
        if (tests.isEmpty()) {
            sendMessage(chatId, "No tests available.");
            showAdminPanel(chatId);
            return;
        }
        
        // Convert collection to list and sort by test number
        List<Test> sortedTests = new ArrayList<>(tests);
        sortedTests.sort((a, b) -> {
            int numA = extractTestNumber(a.getTestId());
            int numB = extractTestNumber(b.getTestId());
            return Integer.compare(numA, numB);
        });
        
        // Keep track of display number
        int displayNumber = 1;
        
        for (Test test : sortedTests) {
            String testId = test.getTestId();
            String buttonText = forRankings ? 
                "📊 Test " + displayNumber : 
                "🗑️ Delete Test " + displayNumber;
            String callbackData = (forRankings ? "rankings_" : "delete_") + testId;
            
            System.out.println("Creating button: " + buttonText + " with data: " + callbackData);
            
            keyboard.add(Collections.singletonList(
                createInlineButton(buttonText, callbackData)));
            
            displayNumber++; // Increment display number
        }
        
        addBackButton(keyboard, "admin");
        
        markup.setKeyboard(keyboard);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(forRankings ? "Select test to view rankings:" : "Select test to delete:");
        message.setReplyMarkup(markup);
        
        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Error showing test list: " + e.getMessage());
            e.printStackTrace();
            showAdminPanel(chatId);
        }
    }

    private void showAvailableTests(Long chatId) {
        Collection<Test> allTests = testService.getAllTests();
        System.out.println("Available tests in database:");
        allTests.forEach(test -> {
            System.out.println("Test ID: " + test.getTestId());
            System.out.println("PDF Path: " + test.getPdfPath());
            System.out.println("Answers: " + test.getAnswers());
            System.out.println("---");
        });
        
        if (allTests.isEmpty()) {
            System.out.println("No tests found in database");
            sendMessage(chatId, "No tests are currently available.");
            return;
        }

        String username = getUserIdentifier(chatId);
        System.out.println("Current user: " + username);
        
        // Convert to list and sort by test number
        List<Test> sortedTests = new ArrayList<>(allTests);
        sortedTests.sort((a, b) -> {
            int numA = extractTestNumber(a.getTestId());
            int numB = extractTestNumber(b.getTestId());
            return Integer.compare(numA, numB);
        });
        
        // Filter available tests
        List<Test> availableTests = sortedTests.stream()
            .filter(test -> !test.getUserScores().containsKey(username))
            .toList();

        if (availableTests.isEmpty()) {
            sendMessage(chatId, "You have taken all available tests.");
            return;
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        // Keep track of display number
        int displayNumber = 1;
        
        for (Test test : availableTests) {
            String buttonText = "📝 Take Test " + displayNumber;
            String callbackData = "take_" + test.getTestId();
            
            System.out.println("Creating button: " + buttonText + " with data: " + callbackData);
            
            keyboard.add(Collections.singletonList(
                createInlineButton(buttonText, callbackData)));
                
            displayNumber++;
        }
        
        addBackButton(keyboard, "student");
        
        markup.setKeyboard(keyboard);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Select a test to participate in:");
        message.setReplyMarkup(markup);
        
        try {
            execute(message);
            System.out.println("Available tests message sent successfully");
        } catch (TelegramApiException e) {
            System.err.println("Error sending available tests message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showUserHistory(Long chatId) {
        String username = getUserIdentifier(chatId);
        Collection<Test> allTests = testService.getAllTests();
        
        if (allTests.isEmpty()) {
            sendMessage(chatId, "📚 No test history found.");
            return;
        }
        
        // Sort tests by number
        List<Test> sortedTests = new ArrayList<>(allTests);
        sortedTests.sort((a, b) -> extractTestNumber(a.getTestId()) - extractTestNumber(b.getTestId()));
        
        // Filter tests for this user
        List<Test> userTests = sortedTests.stream()
            .filter(test -> test.getUserScores().containsKey(username))
            .toList();
        
        if (userTests.isEmpty()) {
            sendMessage(chatId, "📚 You haven't taken any tests yet.");
            return;
        }
        
        StringBuilder history = new StringBuilder();
        history.append("📚 Your Test History:\n\n");
        
        for (Test test : userTests) {
            int totalQuestions = countTotalQuestions(test.getAnswers());
            int userScore = test.getUserScores().get(username);
            double percentage = (userScore * 100.0) / totalQuestions;
            
            // Get user's rank for this test
            int rank = calculateUserRank(username, test);
            int totalParticipants = test.getUserScores().size();
            
            history.append(String.format("""
                Test %d:
                Score: %d/%d (%.1f%%)
                Rank: %d/%d
                
                """,
                extractTestNumber(test.getTestId()),
                userScore,
                totalQuestions,
                percentage,
                rank,
                totalParticipants));
        }
        
        history.append("Press /start to return to main menu.");
        sendMessage(chatId, history.toString());
    }

    private void handleTestSelection(Long chatId, String callbackData) {
        try {
            String[] parts = callbackData.split("_", 2);
            if (parts.length < 2) {
                System.err.println("Invalid callback data format: " + callbackData);
                sendMessage(chatId, "❌ Invalid test selection. Please try again.");
                return;
            }

            String action = parts[0];
            String testId = parts[1];
            
            // Debug logging
            System.out.println("Processing test selection:");
            System.out.println("Callback data: " + callbackData);
            System.out.println("Action: " + action);
            System.out.println("Raw test ID: " + testId);
            
            // Get the test directly without additional formatting
            Test test = testService.getTest(testId);
            if (test == null) {
                System.err.println("Test not found in database. ID: " + testId);
                sendMessage(chatId, "❌ Test not found. Please try again.");
                showMainMenu(chatId);
                return;
            }

            switch (action) {
                case "take":
                case "test":
                    handleTakeTest(chatId, test);
                    break;
                case "delete":
                    handleDeleteTest(chatId, test);
                    break;
                case "edit":
                    handleEditTest(chatId, test);
                    break;
                case "rankings":
                    showTestRankings(chatId, testId);
                    break;
                default:
                    System.err.println("Unknown action: " + action);
                    sendMessage(chatId, "❌ Invalid action. Please try again.");
                    showMainMenu(chatId);
            }
        } catch (Exception e) {
            System.err.println("Error in handleTestSelection: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ An error occurred. Please try again.");
            showMainMenu(chatId);
        }
    }

    private void handleTakeTest(Long chatId, Test test) {
        try {
            String username = getUserIdentifier(chatId);
            if (test.getUserScores().containsKey(username)) {
                sendMessage(chatId, "❌ You have already taken this test.");
                return;
            }

            currentTestId.put(chatId, test.getTestId());
            userStates.put(chatId, "PARTICIPATING_TEST");
            
            // Send PDF and start timer
            sendPdfToStudent(chatId, test);
            startTestTimer(chatId, test.getTestId());
            
        } catch (Exception e) {
            System.err.println("Error starting test: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Error starting test. Please try again.");
            cleanupTestSession(chatId);
        }
    }

    private void handleDeleteTest(Long chatId, Test test) {
        try {
            boolean deleted = testService.deleteTest(test.getTestId());
            if (deleted) {
                sendMessage(chatId, "✅ Test deleted successfully!");
            } else {
                sendMessage(chatId, "❌ Failed to delete test.");
            }
        } catch (Exception e) {
            System.err.println("Error deleting test: " + e.getMessage());
            sendMessage(chatId, "❌ Error deleting test. Please try again.");
        }
        showAdminPanel(chatId);
    }

    private void handleEditTest(Long chatId, Test test) {
        try {
            currentTestId.put(chatId, test.getTestId());
            userStates.put(chatId, "WAITING_EDIT_ANSWERS");
            String message = String.format("""
                Current answers: %s
                
                Please send the new answers in format:
                - For multiple choice: 1a2b3c
                - For text answers: 1(answer)2(answer)
                """, test.getAnswers());
            sendMessage(chatId, message);
        } catch (Exception e) {
            System.err.println("Error preparing test edit: " + e.getMessage());
            sendMessage(chatId, "❌ Error preparing test edit. Please try again.");
            showAdminPanel(chatId);
        }
    }

    private String formatTestId(String testId) {
        if (testId == null || testId.trim().isEmpty()) {
            return "test_" + System.currentTimeMillis();
        }
        
        // Remove all "test_" prefixes and clean the string
        testId = testId.trim().replaceAll("^(test_)+", "");
        
        // If the remaining string starts with a number, just add one "test_" prefix
        if (testId.matches("^\\d.*")) {
            System.out.println("formatTestId: Adding prefix to numeric ID: " + testId);
            return "test_" + testId;
        }
        
        // If we get here, the ID might already have other prefixes, just return it cleaned
        System.out.println("formatTestId: Using ID as is: " + testId);
        return testId;
    }



    private void handleTestAnswers(Long chatId, String text) {
        System.out.println("Processing test answers: " + text);
        
        if (!isValidAnswerFormat(text)) {
            sendMessage(chatId, """
                ❌ Invalid answer format! Please use:
                - For multiple choice: 1a2b3c
                - For text/numeric answers: 1(answer)2(answer)
                - For fractions: 1(3/4)2(2/5)
                
                Examples:
                - "1a2b3c4d5a" for multiple choice
                - "1(3/4)2(2.5)3c" for mixed formats
                - "1(√2)2(∞)3(½)" for special characters
                
                Make sure:
                - Question numbers are in sequence
                - Parentheses are properly matched
                - No spaces in the answer string
                """);
            return;
        }

        TestCreationState state = testCreationStates.get(chatId);
        if (state == null) {
            sendMessage(chatId, "No test creation in progress. Please start over by clicking 'Add Test'.");
            userStates.remove(chatId);
            return;
        }

        try {
            // Store formatted answers
            state.answers = formatAnswers(text);
            
            // Show time limit options
            userStates.put(chatId, "WAITING_TIME_LIMIT");
            showTimeLimitOptions(chatId);
            
        } catch (Exception e) {
            System.err.println("Error processing answers: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "Error processing answers. Please try again.");
            userStates.remove(chatId);
            testCreationStates.remove(chatId);
        }
    }

    private void handleEditAnswers(Long chatId, String text) {
        String testId = currentTestId.get(chatId);
        if (testId == null) {
            sendMessage(chatId, "❌ No test selected for editing.");
            showAdminPanel(chatId);
            return;
        }

        if (!isValidAnswerFormat(text)) {
            sendMessage(chatId, """
                ❌ Invalid answer format! Please use:
                - For multiple choice: 1a2b3c
                - For text answers: 1(answer)2(answer)
                Try again.""");
            return;
        }

        try {
            Test test = testService.getTest(testId);
            if (test != null) {
                test.setAnswers(formatAnswers(text));
                testService.updateTest(testId, test);
                sendMessage(chatId, "✅ Test answers updated successfully!");
                System.out.println("Successfully updated answers for test: " + testId);
            } else {
                sendMessage(chatId, "❌ Test not found. Please try again.");
                System.err.println("Test not found for editing: " + testId);
            }
        } catch (Exception e) {
            System.err.println("Error updating test answers: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Error updating answers. Please try again.");
        } finally {
            userStates.remove(chatId);
            currentTestId.remove(chatId);
            showAdminPanel(chatId);
        }
    }

    private boolean isValidAnswerFormat(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        // Remove any spaces
        text = text.replaceAll("\\s+", "");
        
        int expectedNumber = 1;
        int i = 0;
        
        while (i < text.length()) {
            // Skip if not a digit
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            
            // Found a number, parse it
            StringBuilder numberStr = new StringBuilder();
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                numberStr.append(text.charAt(i));
                i++;
            }
            
            // Validate question number
            int currentNumber;
            try {
                currentNumber = Integer.parseInt(numberStr.toString());
            } catch (NumberFormatException e) {
                System.out.println("Invalid number format: " + numberStr);
                return false;
            }
            
            if (currentNumber != expectedNumber) {
                System.out.println("Invalid question number sequence. Expected: " + expectedNumber + ", Got: " + currentNumber);
                return false;
            }
            expectedNumber++;
            
            // After number must come either a letter or opening parenthesis
            if (i < text.length()) {
                char nextChar = text.charAt(i);
                if (nextChar == '(') {
                    // Skip content in parentheses
                    i++;
                    while (i < text.length() && text.charAt(i) != ')') {
                        // Allow numbers, letters, '/', '.', and special characters inside parentheses
                        char c = text.charAt(i);
                        if (!Character.isLetterOrDigit(c) && c != '/' && c != '.' && c != '-' && c != '√' && c != '∞') {
                            System.out.println("Invalid character inside parentheses: " + c);
                            return false;
                        }
                        i++;
                    }
                    if (i >= text.length()) {
                        System.out.println("Unclosed parentheses");
                        return false;
                    }
                    i++; // Skip closing parenthesis
                } else if (Character.isLetter(nextChar)) {
                    i++; // Skip the letter answer
                } else {
                    System.out.println("Invalid character after number: " + nextChar);
                    return false;
                }
            }
        }
        
        return true;
    }

    private String formatAnswers(String text) {
        // Remove any spaces
        text = text.replaceAll("\\s+", "");
        
        StringBuilder formatted = new StringBuilder();
        boolean inParentheses = false;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            
            if (Character.isDigit(c)) {
                // Handle multi-digit question numbers
                StringBuilder number = new StringBuilder();
                while (i < text.length() && Character.isDigit(text.charAt(i))) {
                    number.append(text.charAt(i));
                    i++;
                }
                formatted.append(number);
                i--; // Adjust index since we'll increment in the loop
                continue;
            }
            
            if (c == '(') {
                inParentheses = true;
                formatted.append(c);
            } else if (c == ')') {
                inParentheses = false;
                formatted.append(c);
            } else if (inParentheses) {
                // Keep everything inside parentheses as is
                formatted.append(c);
            } else {
                // Convert to lowercase outside parentheses
                formatted.append(Character.toLowerCase(c));
            }
        }
        
        System.out.println("Formatted answers: " + formatted);
        return formatted.toString();
    }

    private void handleTestSubmission(Long chatId, String answers, Update update) {
        System.out.println("Processing test submission for chat ID: " + chatId);
        
        // Validate test session
        if (!currentTestId.containsKey(chatId)) {
            sendMessage(chatId, "❌ No active test session found. Please start a new test.");
            cleanupTestSession(chatId);
            return;
        }

        String testId = currentTestId.get(chatId);
        Test test = testService.getTest(testId);
        if (test == null) {
            sendMessage(chatId, "❌ Test not found. Please contact admin.");
            cleanupTestSession(chatId);
            return;
        }

        // Verify time limit hasn't expired
        LocalDateTime startTime = testStartTimes.get(chatId);
        if (startTime != null && test.getTimeLimit() > 0) {
            long minutesElapsed = ChronoUnit.MINUTES.between(startTime, LocalDateTime.now());
            if (minutesElapsed > test.getTimeLimit()) {
                sendMessage(chatId, """
                    ⏰ Time limit exceeded!
                    Your answers cannot be submitted.
                    
                    Please try the test again if allowed.""");
                cleanupTestSession(chatId);
                return;
            }
        }

        try {
            answers = formatAnswers(answers);
            if (!isValidAnswerFormat(answers)) {
                sendMessage(chatId, """
                    ❌ Invalid answer format! Please use:
                    - For multiple choice: 1a2b3c
                    - For text answers: 1(answer)2(answer)
                    
                    Example: 1a2b3c4d5a or 1(text)2(3.14)3c
                    
                    Try submitting your answers again.""");
                return;
            }

            // Get correct answers
            String correctAnswers = test.getAnswers();
            
            // Calculate score and track incorrect answers
            List<String> incorrectQuestions = new ArrayList<>();
            int score = 0;
            int currentQuestion = 0;
            
            // Parse and compare answers
            int i = 0;
            while (i < Math.min(answers.length(), correctAnswers.length())) {
                if (!Character.isDigit(answers.charAt(i))) {
                    i++;
                    continue;
                }
                
                // Get question number
                StringBuilder questionNum = new StringBuilder();
                while (i < answers.length() && Character.isDigit(answers.charAt(i))) {
                    questionNum.append(answers.charAt(i));
                    i++;
                }
                currentQuestion = Integer.parseInt(questionNum.toString());
                
                if (i >= answers.length()) break;
                
                // Compare answers
                if (answers.charAt(i) == '(' && i < correctAnswers.length() && correctAnswers.charAt(i) == '(') {
                    // Text answer
                    int userEnd = answers.indexOf(')', i);
                    int correctEnd = correctAnswers.indexOf(')', i);
                    
                    if (userEnd != -1 && correctEnd != -1) {
                        String userAns = answers.substring(i, userEnd + 1);
                        String correctAns = correctAnswers.substring(i, correctEnd + 1);
                        
                        if (userAns.equalsIgnoreCase(correctAns)) {
                            score++;
                        } else {
                            incorrectQuestions.add(String.format("Q%d: Your answer: %s, Correct: %s",
                                currentQuestion, userAns, correctAns));
                        }
                        
                        i = Math.max(userEnd, correctEnd) + 1;
                    }
                } else {
                    // Multiple choice answer
                    char userAnswer = Character.toLowerCase(answers.charAt(i));
                    char correctAnswer = Character.toLowerCase(correctAnswers.charAt(i));
                    
                    if (userAnswer == correctAnswer) {
                        score++;
                    } else {
                        incorrectQuestions.add(String.format("Q%d: Your answer: %c, Correct: %c",
                            currentQuestion, userAnswer, correctAnswer));
                    }
                    i++;
                }
            }

            int totalQuestions = countTotalQuestions(correctAnswers);
            double percentage = (score * 100.0) / totalQuestions;
            
            // Get user information and save score
            String username = getUsernameFromTelegramUser(update.getMessage().getFrom());
            test.addUserScore(username, score);
            testService.updateTest(testId, test);

            // Build feedback message
            StringBuilder feedbackMessage = new StringBuilder();
            feedbackMessage.append(String.format("""
                📋 Test Results for %s:
                
                Score: %d/%d (%.1f%%)
                """, username, score, totalQuestions, percentage));

            // Add incorrect answers section if any
            if (!incorrectQuestions.isEmpty()) {
                feedbackMessage.append("\n❌ Incorrect Answers:\n");
                for (String incorrect : incorrectQuestions) {
                    feedbackMessage.append(incorrect).append("\n");
                }
            } else {
                feedbackMessage.append("\n✅ Perfect score! All answers correct!\n");
            }

            // Add performance feedback
            if (percentage >= 90) {
                feedbackMessage.append("\n🌟 Outstanding performance!");
            } else if (percentage >= 80) {
                feedbackMessage.append("\n✨ Great job! Keep it up!");
            } else if (percentage >= 70) {
                feedbackMessage.append("\n👍 Good effort! Room for improvement.");
            } else if (percentage >= 60) {
                feedbackMessage.append("\n💪 Fair attempt. More practice needed.");
            } else {
                feedbackMessage.append("\n📚 Additional study recommended.");
            }

            // Add rank information
            int rank = calculateUserRank(username, test);
            int totalParticipants = test.getUserScores().size();
            feedbackMessage.append(String.format("""
                
                📊 Your Rank: %d/%d
                
                Press /start to return to main menu.""",
                rank, totalParticipants));

            sendMessage(chatId, feedbackMessage.toString());

        } catch (Exception e) {
            System.err.println("Error processing test submission: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, """
                ❌ Error processing your submission.
                Please try again or contact admin if the problem persists.""");
        } finally {
            cleanupTestSession(chatId);
        }
    }

    private String getUsernameFromTelegramUser(org.telegram.telegrambots.meta.api.objects.User telegramUser) {
        if (telegramUser.getUserName() != null) {
            return "@" + telegramUser.getUserName();
        }
        String firstName = telegramUser.getFirstName();
        String lastName = telegramUser.getLastName();
        return (firstName + (lastName != null ? " " + lastName : "")).trim();
    }

    private void saveTestResults(Long chatId, String testId, String username, int score, int totalQuestions) {
        // Update test scores
        Test test = testService.getTest(testId);
        test.addUserScore(username, score);
        testService.updateTest(testId, test);

        // No need for separate history entry as we'll use the test scores directly
        double percentage = (score * 100.0) / totalQuestions;
        String completionMessage = String.format("""
            ✅ Test completed!
            
            Score: %d/%d (%.1f%%)
            
            You can view your complete history in the student panel.""",
            score, totalQuestions, percentage);
        
        sendMessage(chatId, completionMessage);
    }

    private void sendTestCompletionFeedback(Long chatId, String username, int score, int totalQuestions, double percentage) {
        String feedbackMessage = String.format("""
            ✅ Test submitted successfully!
            
            👤 Student: %s
            📊 Score: %d/%d (%.1f%%)
            
            You can:
            - View your ranking in the admin panel
            - Check your history in the student panel
            
            Press /start to return to main menu.""", 
            username, score, totalQuestions, percentage);
        
        sendMessage(chatId, feedbackMessage);
    }

    private void cleanupTestSession(Long chatId) {
        currentTestId.remove(chatId);
        testStartTimes.remove(chatId);
        userStates.remove(chatId);
        temporaryAnswers.remove(chatId);
        testCreationStates.remove(chatId);
    }

    private int calculateScore(String userAnswers, String correctAnswers) {
        System.out.println("Calculating score:");
        System.out.println("User answers: " + userAnswers);
        System.out.println("Correct answers: " + correctAnswers);
        
        int score = 0;
        int i = 0;
        
        while (i < Math.min(userAnswers.length(), correctAnswers.length())) {
            // Skip if not at the start of a question (must start with a number)
            if (!Character.isDigit(userAnswers.charAt(i))) {
                i++;
                continue;
            }
            
            // Skip the question number
            while (i < userAnswers.length() && Character.isDigit(userAnswers.charAt(i))) {
                i++;
            }
            
            if (i >= userAnswers.length()) break;
            
            // Check if it's a parentheses answer or single letter
            if (userAnswers.charAt(i) == '(' && i < correctAnswers.length() && correctAnswers.charAt(i) == '(') {
                // Handle parentheses answers
                int userEnd = userAnswers.indexOf(')', i);
                int correctEnd = correctAnswers.indexOf(')', i);
                
                if (userEnd != -1 && correctEnd != -1) {
                    String userAns = userAnswers.substring(i, userEnd + 1);
                    String correctAns = correctAnswers.substring(i, correctEnd + 1);
                    
                    if (userAns.equalsIgnoreCase(correctAns)) {
                        score++;
                    }
                    
                    i = Math.max(userEnd, correctEnd) + 1;
                } else {
                    i++;
                }
            } else {
                // Handle single letter answers
                if (i < correctAnswers.length() && 
                    Character.toLowerCase(userAnswers.charAt(i)) == 
                    Character.toLowerCase(correctAnswers.charAt(i))) {
                    score++;
                }
                i++;
            }
        }
        
        System.out.println("Final score: " + score);
        return score;
    }

    private int countTotalQuestions(String answers) {
        int maxQuestionNumber = 0;
        int i = 0;
        boolean insideParentheses = false;
        
        while (i < answers.length()) {
            char currentChar = answers.charAt(i);
            
            // Track if we're inside parentheses
            if (currentChar == '(') {
                insideParentheses = true;
                i++;
                continue;
            } else if (currentChar == ')') {
                insideParentheses = false;
                i++;
                continue;
            }
            
            // Only process numbers if we're not inside parentheses
            if (!insideParentheses && Character.isDigit(currentChar)) {
                StringBuilder numberStr = new StringBuilder();
                while (i < answers.length() && Character.isDigit(answers.charAt(i))) {
                    numberStr.append(answers.charAt(i));
                    i++;
                }
                
                try {
                    int questionNumber = Integer.parseInt(numberStr.toString());
                    maxQuestionNumber = Math.max(maxQuestionNumber, questionNumber);
                } catch (NumberFormatException e) {
                    // Skip invalid numbers
                }
            } else {
                i++;
            }
        }
        
        System.out.println("Total questions found: " + maxQuestionNumber);
        return maxQuestionNumber;
    }

    private void startTestTimer(Long chatId, String testId) {
        Test test = testService.getTest(testId);
        if (test == null) return;

        int timeLimit = test.getTimeLimit();
        testStartTimes.put(chatId, LocalDateTime.now());

        if (timeLimit <= 0) {
            sendMessage(chatId, """
                📝 Test started! No time limit.
                
                Submit your answers in format:
                - Multiple choice: 1a2b3c
                - Text answers: 1(answer)2(answer)
                
                Good luck!""");
            return;
        }

        // Schedule warning at 75% of time
        int warningTime = (int)(timeLimit * 0.75);
        scheduler.schedule(() -> {
            if (currentTestId.containsKey(chatId)) {
                sendMessage(chatId, String.format("️ Warning: %d minutes remaining!", timeLimit - warningTime));
            }
        }, warningTime, TimeUnit.MINUTES);

        // Schedule final warning at 90% of time
        int finalWarningTime = (int)(timeLimit * 0.9);
        scheduler.schedule(() -> {
            if (currentTestId.containsKey(chatId)) {
                sendMessage(chatId, String.format("⚠️ Final warning: Only %d minutes left!", timeLimit - finalWarningTime));
            }
        }, finalWarningTime, TimeUnit.MINUTES);

        // Schedule test end
        scheduler.schedule(() -> {
            if (currentTestId.containsKey(chatId)) {
                sendMessage(chatId, "⏰ Time's up! Test session ended.");
                cleanupTestSession(chatId);
            }
        }, timeLimit, TimeUnit.MINUTES);

        sendMessage(chatId, String.format("""
            📝 Test started! Time limit: %d minutes
            
            Submit your answers in format:
            - Multiple choice: 1a2b3c
            - Text answers: 1(answer)2(answer)
            
            You will receive warnings at:
            - %d minutes remaining
            - %d minutes remaining
            
            Good luck!""", 
            timeLimit, timeLimit - warningTime, timeLimit - finalWarningTime));
    }

    private void showTestRankings(Long chatId, String testId) {
        System.out.println("Showing rankings for test: " + testId);
        
        Test test = testService.getTest(testId);
        if (test == null) {
            sendMessage(chatId, "❌ Test not found.");
            return;
        }
        
        Map<String, Integer> scores = test.getUserScores();
        int totalQuestions = countTotalQuestions(test.getAnswers());
        
        StringBuilder rankings = new StringBuilder();
        rankings.append("📊 Rankings for Test ").append(extractTestNumber(testId)).append(":\n\n");
        
        if (scores == null || scores.isEmpty()) {
            rankings.append("No students have taken this test yet.\n\n");
        } else {
            // Sort users by score (highest first)
            List<Map.Entry<String, Integer>> sortedScores = new ArrayList<>(scores.entrySet());
            sortedScores.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            
            int rank = 1;
            int prevScore = -1;
            int sameRankCount = 0;
            
            for (Map.Entry<String, Integer> entry : sortedScores) {
                String username = entry.getKey();
                int score = entry.getValue();
                
                // Handle tied scores
                if (score != prevScore) {
                    rank += sameRankCount;
                    sameRankCount = 0;
                } else {
                    sameRankCount++;
                }
                
                double percentage = (score * 100.0) / totalQuestions;
                rankings.append(String.format("%d. %s: %d/%d (%.1f%%)\n",
                    rank, username, score, totalQuestions, percentage));
                
                prevScore = score;
            }
        }
        
        // Add test information
        rankings.append("\n📝 Test Information:\n");
        rankings.append("Total questions: ").append(totalQuestions).append("\n");
        rankings.append("Time limit: ").append(test.getTimeLimit() == -1 ? "No limit" : test.getTimeLimit() + " minutes");
        
        sendMessage(chatId, rankings.toString());
    }

    // Add this method to send PDF file to student
    private void sendPdfToStudent(Long chatId, Test test) {
        try {
            SendDocument document = new SendDocument();
            document.setChatId(chatId.toString());
            // Create InputFile from java.io.File
            document.setDocument(new InputFile(new java.io.File(test.getPdfPath())));
            document.setCaption("Here is your test paper. Good luck!");
            execute(document);
        } catch (TelegramApiException e) {
            sendMessage(chatId, "Error sending test file. Please contact admin.");
            e.printStackTrace();
        }
    }

    // Add this method to handle time limit selection
    private void showTimeLimitOptions(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        // Add time limit options
        int[] timeOptions = {5, 10, 15, 30, 45, 60, -1}; // -1 for no limit
        for (int time : timeOptions) {
            String buttonText = time == -1 ? "No Time Limit" : time + " minutes";
            String callbackData = "time_" + time;
            keyboard.add(Collections.singletonList(
                createInlineButton(buttonText, callbackData)));
        }
        
        markup.setKeyboard(keyboard);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Select time limit for the test:");
        message.setReplyMarkup(markup);
        
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Add method to handle time limit selection
    private void handleTimeLimitSelection(Long chatId, int timeLimit) {
        TestCreationState state = testCreationStates.get(chatId);
        if (state == null) {
            sendMessage(chatId, "❌ Error: Test creation data not found. Please try again.");
            showAdminPanel(chatId);
            return;
        }

        try {
            state.timeLimit = timeLimit;
            
            // Create test with empty ID (will be generated by DatabaseDao)
            Test test = new Test("", state.pdfPath, state.answers, timeLimit);
            testService.addTest(test);

            // After successful creation, rename the PDF file
            try {
                Path oldPath = Paths.get(state.pdfPath);
                Path newPath = Paths.get("tests/" + test.getTestId() + ".pdf");
                Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                test.setPdfPath(newPath.toString());
                testService.updateTest(test.getTestId(), test);
                
                String timeLimitText = timeLimit == -1 ? "no time limit" : timeLimit + " minutes";
                String confirmationMessage = String.format("""
                    ✅ Test created successfully!
                    
                    🆔 Test ID: %s
                    📝 Answers: %s
                    ⏱️ Time limit: %s
                    
                    Returning to admin panel...""", 
                    test.getTestId(), state.answers, timeLimitText);
                    
                sendMessage(chatId, confirmationMessage);
                
                // Wait a moment before showing admin panel
                Thread.sleep(1000);
                showAdminPanel(chatId);
                
            } catch (IOException | InterruptedException e) {
                System.err.println("Error finalizing test creation: " + e.getMessage());
                e.printStackTrace();
                sendMessage(chatId, "❌ Error saving test. Please try again.");
                showAdminPanel(chatId);
            }
            
        } catch (Exception e) {
            System.err.println("Error creating test: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Error creating test. Please try again.");
            showAdminPanel(chatId);
        } finally {
            // Cleanup
            userStates.remove(chatId);
            testCreationStates.remove(chatId);
        }
    }

    // Add these methods for back buttons
    private void addBackButton(List<List<InlineKeyboardButton>> keyboard, String backTo) {
        keyboard.add(Collections.singletonList(
            createInlineButton("⬅️ Back", "back_" + backTo)));
    }

    // Add this new method to show tests for editing
    private void showTestListForEdit(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        Collection<Test> tests = testService.getAllTests();
        if (tests.isEmpty()) {
            sendMessage(chatId, "No tests available to edit.");
            showAdminPanel(chatId);
            return;
        }
        
        // Convert to list and sort by test number
        List<Test> sortedTests = new ArrayList<>(tests);
        sortedTests.sort((a, b) -> {
            int numA = extractTestNumber(a.getTestId());
            int numB = extractTestNumber(b.getTestId());
            return Integer.compare(numA, numB);
        });
        
        // Keep track of display number
        int displayNumber = 1;
        
        for (Test test : sortedTests) {
            String testId = test.getTestId();
            String buttonText = "✏️ Edit Test " + displayNumber;
            String callbackData = "edit_" + testId;
            
            System.out.println("Creating edit button: " + buttonText + " with data: " + callbackData);
            
            keyboard.add(Collections.singletonList(
                createInlineButton(buttonText, callbackData)));
            
            displayNumber++; // Increment display number
        }
        
        addBackButton(keyboard, "admin");
        
        markup.setKeyboard(keyboard);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Select test to edit:");
        message.setReplyMarkup(markup);
        
        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Error showing test list: " + e.getMessage());
            e.printStackTrace();
            showAdminPanel(chatId);
        }
    }

    // Add this class inside TestBot
    private static class TestCreationState {
        String pdfPath;
        String answers;
        int timeLimit = -1;
    }

    // Add helper method to extract test number
    private int extractTestNumber(String testId) {
        try {
            // Extract only the numeric part after "test_"
            if (testId.startsWith("test_")) {
                String numStr = testId.substring(5); // Skip "test_"
                return Integer.parseInt(numStr);
            }
            // Fallback to old behavior
            String numStr = testId.replaceAll("[^0-9]", "");
            return numStr.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // Put malformed IDs at the end
        }
    }

    // Add new method to handle admin management
    private void showAdminManagement(Long chatId) {
        userStates.put(chatId, "WAITING_NEW_ADMIN_ID");
        sendMessage(chatId, "Please enter the chat ID of the new admin:");
    }

    // Add new method to handle new admin ID
    private void handleNewAdminId(Long chatId, String text) {
        try {
            Long newAdminId = Long.parseLong(text);
            databaseDao.addAdmin(newAdminId, "Admin_" + newAdminId);
            sendMessage(chatId, "New admin added successfully!");
            showAdminPanel(chatId);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid chat ID. Please enter a valid number.");
        }
        userStates.remove(chatId);
    }

    // Helper method for simple messages
    private void sendOrEditMessage(Long chatId, String text, InlineKeyboardMarkup markup) {
        Integer messageId = lastMessageId.get(chatId);
        if (messageId != null) {
            try {
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(chatId.toString());
                editMessage.setMessageId(messageId);
                editMessage.setText(text);
                if (markup != null) {
                    editMessage.setReplyMarkup(markup);
                }
                execute(editMessage);
                return;
            } catch (TelegramApiException e) {
                // Ignore "message is not modified" error
                if (!e.getMessage().contains("message is not modified")) {
                    System.err.println("Failed to edit message: " + e.getMessage());
                }
            }
        }

        // Send new message only if editing failed or no previous message exists
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        if (markup != null) {
            message.setReplyMarkup(markup);
        }

        try {
            Message sentMessage = execute(message);
            lastMessageId.put(chatId, sentMessage.getMessageId());
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }

    private void handleDocumentError(Long chatId, Exception e) {
        System.err.println("Error processing document: " + e.getMessage());
        e.printStackTrace();
        sendMessage(chatId, "❌ Error processing file. Please try again.");
        userStates.remove(chatId);
        testCreationStates.remove(chatId);
    }

    private void handleAddTest(Long chatId) {
        System.out.println("Add test button clicked for chat ID: " + chatId);
        userStates.put(chatId, "WAITING_PDF");
        try {
            Files.createDirectories(Paths.get("tests"));
            System.out.println("Tests directory created/verified");
            sendMessage(chatId, "Please send the PDF file for the test.");
            System.out.println("Waiting for PDF file from user");
        } catch (IOException e) {
            System.err.println("Error creating tests directory: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Error preparing for test creation. Please try again.");
            showAdminPanel(chatId);
        }
    }

    // Add this new method to clean up all states
    private void cleanupAllStates(Long chatId) {
        currentTestId.remove(chatId);
        testStartTimes.remove(chatId);
        userStates.remove(chatId);
        temporaryAnswers.remove(chatId);
        testCreationStates.remove(chatId);
        users.remove(chatId);  // Clear user state
        lastMessageId.remove(chatId);
    }

    // Helper method to calculate user's rank in a test
    private int calculateUserRank(String username, Test test) {
        Map<String, Integer> scores = test.getUserScores();
        int userScore = scores.get(username);
        
        // Count how many users have a higher score
        int rank = 1;
        for (int score : scores.values()) {
            if (score > userScore) rank++;
        }
        
        return rank;
    }
} 