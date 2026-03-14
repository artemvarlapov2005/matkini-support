package org.ledger.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram.bot")
data class BotProperties(
    val token: String,
    val adminIds: Set<Long>,
    val username: String = "MatkiniSupportBot"
)