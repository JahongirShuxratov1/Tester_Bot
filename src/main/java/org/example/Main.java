package org.example;

import org.example.bot.TestBot;
import org.example.dao.DatabaseDao;
import org.example.dao.DatabaseDaoImpl;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Main {
    public static void main(String[] args) {
        try {
            // Load configuration
            Properties config = loadConfig();
            
            // Initialize database connection
            DatabaseDao databaseDao = new DatabaseDaoImpl(
                config.getProperty("database.url"),
                config.getProperty("database.username"),
                config.getProperty("database.password")
            );

            // Create bot instance
            TestBot bot = new TestBot(databaseDao);

            // Register bot
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);

            System.out.println("Bot started successfully!");
            System.out.println("Username: " + bot.getBotUsername());

        } catch (TelegramApiException e) {
            System.err.println("Error starting bot: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Properties loadConfig() throws IOException {
        Properties config = new Properties();
        try (InputStream input = Main.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IOException("Unable to find config.properties");
            }
            config.load(input);
        }
        return config;
    }
}