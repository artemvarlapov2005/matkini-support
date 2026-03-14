package org.ledger.config

import org.ledger.bot.SupportBot
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

@Configuration
class BotConfiguration(private val supportBot: SupportBot) {

    @Bean
    fun telegramBotsApi(): TelegramBotsApi {
        val api = TelegramBotsApi(DefaultBotSession::class.java)
        api.registerBot(supportBot)
        return api
    }
}
