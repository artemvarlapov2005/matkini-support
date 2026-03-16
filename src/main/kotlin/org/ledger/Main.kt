package org.ledger

import org.ledger.config.BotProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(BotProperties::class)
@EnableScheduling
class MatkiniSupportApplication

fun main(args: Array<String>) {
    runApplication<MatkiniSupportApplication>(*args)
}
