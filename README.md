# Telegram Test Bot

A Telegram bot for managing and taking tests.

## Deployment

This application is configured for deployment on Railway.app via GitHub.

### Prerequisites

- Java 17
- Maven
- Docker (for local testing)

### Environment Variables

Set these in Railway.app dashboard:

- `BOT_TOKEN`: Your Telegram bot token
- `BOT_USERNAME`: Your bot's username
- `DATABASE_URL`: Your database connection URL
- `DATABASE_USERNAME`: Database username
- `DATABASE_PASSWORD`: Database password

### Deployment Steps

1. Fork this repository
2. Connect to Railway.app
3. Create new project from GitHub repo
4. Add environment variables
5. Deploy

## Local Development 