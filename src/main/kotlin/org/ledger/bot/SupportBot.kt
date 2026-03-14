package org.ledger.bot

import org.ledger.config.BotProperties
import org.ledger.model.MediaType
import org.ledger.model.SenderType
import org.ledger.model.Ticket
import org.ledger.model.TicketMessage
import org.ledger.model.TicketStatus
import org.ledger.service.TicketService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class SupportBot(
    botProperties: BotProperties,
    private val ticketService: TicketService
) : TelegramLongPollingBot(botProperties.token) {

    private val log = LoggerFactory.getLogger(SupportBot::class.java)
    private val adminIds = botProperties.adminIds
    private val botUsername = botProperties.username

    private val tz = ZoneId.of("Europe/Moscow")
    private val timeFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    override fun getBotUsername() = botUsername

    override fun onUpdateReceived(update: Update) {
        when {
            update.hasCallbackQuery() -> handleCallback(update)
            update.hasMessage() && hasContent(update.message) -> handleMessage(update)
        }
    }

    // ── Content detection ────────────────────────────────────────────────────

    private fun hasContent(msg: Message): Boolean =
        msg.hasText() || msg.hasVoice() || msg.hasPhoto() || msg.hasDocument() ||
        msg.hasVideo() || msg.hasAudio() || msg.hasVideoNote()

    private fun extractMedia(msg: Message): Pair<MediaType?, String?> = when {
        msg.hasVoice()     -> MediaType.VOICE      to msg.voice.fileId
        msg.hasVideoNote() -> MediaType.VIDEO_NOTE to msg.videoNote.fileId
        msg.hasVideo()     -> MediaType.VIDEO      to msg.video.fileId
        msg.hasAudio()     -> MediaType.AUDIO      to msg.audio.fileId
        msg.hasDocument()  -> MediaType.DOCUMENT   to msg.document.fileId
        msg.hasPhoto()     -> MediaType.PHOTO      to msg.photo.maxByOrNull { it.fileSize }!!.fileId
        else               -> null to null
    }

    // ── Message routing ──────────────────────────────────────────────────────

    private fun handleMessage(update: Update) {
        val msg = update.message
        if (msg.chatId in adminIds) handleAdminMessage(msg) else handleUserMessage(msg)
    }

    // ── User side ────────────────────────────────────────────────────────────

    private fun handleUserMessage(msg: Message) {
        val chatId = msg.chatId
        val text = msg.text ?: msg.caption ?: ""
        val (mediaType, fileId) = extractMedia(msg)

        if (text == "/start" && mediaType == null) {
            sendText(chatId,
                "Добро пожаловать в поддержку Matkini Network!\n\n" +
                "Напишите ваш вопрос или опишите проблему — мы постараемся помочь."
            )
            return
        }

        val existingTicket = ticketService.getActiveTicketByUser(chatId)

        if (existingTicket == null) {
            val userName = buildUserName(msg.from)
            val ticket = ticketService.createTicket(chatId, userName)
            ticketService.saveMessage(ticket.id, text, SenderType.USER, ticket.userName, mediaType, fileId)
            notifyAdmins(ticket, text.ifEmpty { mediaType?.displayName() ?: "" })
            sendText(chatId, "✅ Обращение #${ticket.id} создано. Оператор скоро вам ответит.")
        } else {
            val saved = ticketService.saveMessage(
                existingTicket.id, text, SenderType.USER, existingTicket.userName, mediaType, fileId
            )
            if (existingTicket.status == TicketStatus.IN_PROGRESS) {
                val adminChatId = existingTicket.adminChatId!!
                if (mediaType != null) {
                    sendMedia(adminChatId, fileId!!, mediaType, mediaCaption(saved, existingTicket.id))
                } else {
                    sendText(adminChatId, "💬 Пользователь [#${existingTicket.id}]: $text")
                }
            }
        }
    }

    // ── Admin side ───────────────────────────────────────────────────────────

    private fun handleAdminMessage(msg: Message) {
        val chatId = msg.chatId
        val text = msg.text ?: msg.caption ?: ""
        val (mediaType, fileId) = extractMedia(msg)

        // Commands are text-only
        if (mediaType == null) {
            when (text) {
                "/start" -> {
                    sendText(chatId,
                        "Панель поддержки Matkini Network\n\n" +
                        "Команды:\n" +
                        "/tickets — все обращения\n" +
                        "/leave — отключиться от обращения (оставить открытым)\n" +
                        "/close — закрыть текущее обращение"
                    )
                    return
                }
                "/tickets" -> {
                    val (msgText, keyboard) = buildTicketsList()
                    execute(SendMessage.builder().chatId(chatId).text(msgText).replyMarkup(keyboard).build())
                    return
                }
                "/leave" -> {
                    val ticket = ticketService.getActiveTicketByAdmin(chatId)
                    if (ticket == null) {
                        sendText(chatId, "У вас нет активного обращения.")
                    } else {
                        ticketService.leaveTicket(ticket.id)
                        sendText(chatId, "↩️ Вы отключились от обращения #${ticket.id}. Оно снова открыто.")
                        sendText(ticket.userChatId, "ℹ️ Оператор временно отключился от вашего обращения #${ticket.id}. Ожидайте — скоро кто-то подключится.")
                        renotifyAdmins(ticket.id)
                    }
                    return
                }
                "/close" -> {
                    val ticket = ticketService.getActiveTicketByAdmin(chatId)
                    if (ticket == null) {
                        sendText(chatId, "У вас нет активного обращения.")
                    } else {
                        ticketService.closeTicket(ticket.id)
                        sendText(chatId, "✅ Обращение #${ticket.id} закрыто.")
                        sendText(ticket.userChatId, "✅ Ваше обращение #${ticket.id} закрыто. Спасибо за обращение в Matkini Network!")
                    }
                    return
                }
            }
        }

        val ticket = ticketService.getActiveTicketByAdmin(chatId)
        if (ticket == null) {
            sendText(chatId, "У вас нет активного обращения. Возьмите обращение из уведомления или /tickets.")
            return
        }

        val adminName = ticket.adminName ?: "Оператор"
        val saved = ticketService.saveMessage(ticket.id, text, SenderType.ADMIN, adminName, mediaType, fileId)
        if (mediaType != null) {
            sendMedia(ticket.userChatId, fileId!!, mediaType, mediaCaption(saved, ticket.id, forUser = true, adminName = adminName))
        } else {
            sendText(ticket.userChatId, "💬 $adminName: $text")
        }
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    private fun handleCallback(update: Update) {
        val cb = update.callbackQuery
        val adminChatId: Long = cb.from.id

        if (adminChatId !in adminIds) {
            answerCallback(cb.id, "⛔ У вас нет прав.")
            return
        }

        when {
            cb.data == "tickets_list" -> {
                answerCallback(cb.id, "")
                val (msgText, keyboard) = buildTicketsList()
                editMessage(cb.message.chatId, cb.message.messageId, msgText, keyboard)
            }
            cb.data.startsWith("view_ticket:") -> {
                answerCallback(cb.id, "")
                val ticketId = cb.data.removePrefix("view_ticket:").toLong()
                showTicketHistory(adminChatId, cb.message.chatId, cb.message.messageId, ticketId)
            }
            cb.data.startsWith("take_ticket:") -> handleTakeTicket(cb, adminChatId)
        }
    }

    private fun handleTakeTicket(cb: CallbackQuery, adminChatId: Long) {
        val ticketId = cb.data.removePrefix("take_ticket:").toLong()

        if (ticketService.getActiveTicketByAdmin(adminChatId) != null) {
            answerCallback(cb.id, "Сначала закройте текущее обращение: /close")
            return
        }

        val adminName = buildUserName(cb.from)
        if (!ticketService.assignAdmin(ticketId, adminChatId, adminName)) {
            answerCallback(cb.id, "Обращение уже взято другим оператором.")
            removeTicketButton(adminChatId, cb.message.messageId, ticketId, takenByOther = true)
            return
        }

        val ticket = ticketService.getTicketById(ticketId)!!
        answerCallback(cb.id, "Вы взяли обращение #$ticketId")
        sendText(adminChatId,
            "✅ Вы взяли обращение #$ticketId от ${ticket.userName}.\n" +
            "Отвечайте прямо в этот чат. Для закрытия используйте /close"
        )
        sendText(ticket.userChatId, "✅ Оператор подключился к вашему обращению #$ticketId. Можете продолжать писать.")
        sendMessageHistory(adminChatId, ticketId)

        for ((admId, msgId) in ticket.adminNotificationMessages) {
            removeTicketButton(admId, msgId, ticketId,
                takenByOther = admId != adminChatId,
                takenByName = if (admId != adminChatId) adminName else null
            )
        }
    }

    // ── Ticket list & history ────────────────────────────────────────────────

    private fun buildTicketsList(): Pair<String, InlineKeyboardMarkup> {
        val tickets = ticketService.getAllTickets().take(30)
        if (tickets.isEmpty()) {
            return "Обращений пока нет." to InlineKeyboardMarkup.builder().keyboard(emptyList()).build()
        }

        val text = "Все обращения (${tickets.size}):\n🟡 — открыто  🟢 — в работе  ⚫ — закрыто"
        val keyboard = InlineKeyboardMarkup.builder()
            .keyboard(tickets.map { t ->
                listOf(
                    InlineKeyboardButton.builder()
                        .text("${t.status.icon()} #${t.id} · ${t.userName.take(28)}")
                        .callbackData("view_ticket:${t.id}")
                        .build()
                )
            })
            .build()
        return text to keyboard
    }

    private fun showTicketHistory(adminChatId: Long, chatId: Long, messageId: Int, ticketId: Long) {
        val ticket = ticketService.getTicketById(ticketId)
        if (ticket == null) {
            editMessage(chatId, messageId, "Обращение #$ticketId не найдено.", backKeyboard())
            return
        }

        val messages = ticketService.getMessages(ticketId)
        val statusLine = when (ticket.status) {
            TicketStatus.OPEN        -> "🟡 Открыто"
            TicketStatus.IN_PROGRESS -> "🟢 В работе · ${ticket.adminName}"
            TicketStatus.CLOSED      -> "⚫ Закрыто"
        }

        val sb = StringBuilder()
        sb.append("Обращение #${ticket.id}\n")
        sb.append("От: ${ticket.userName}\n")
        sb.append("Статус: $statusLine\n")

        if (messages.isEmpty()) {
            sb.append("\nИстория пуста.")
        } else {
            sb.append("─".repeat(20)).append("\n")
            messages.forEach { msg ->
                val time = msg.sentAt.atZone(tz).format(timeFmt)
                val icon = if (msg.senderType == SenderType.USER) "👤" else "🛠"
                if (msg.mediaType != null) {
                    val label = "${msg.mediaType!!.displayName()} #${msg.mediaIndex}"
                    val captionPart = if (msg.text.isNotEmpty()) " — ${msg.text}" else ""
                    sb.append("[$time] $icon ${msg.senderName}: [$label$captionPart]\n\n")
                } else {
                    sb.append("[$time] $icon ${msg.senderName}:\n${msg.text}\n\n")
                }
            }
        }

        val buttons = mutableListOf<List<InlineKeyboardButton>>()
        if (ticket.status == TicketStatus.OPEN) {
            buttons.add(listOf(
                InlineKeyboardButton.builder()
                    .text("✋ Взять обращение")
                    .callbackData("take_ticket:$ticketId")
                    .build()
            ))
        }
        buttons.add(listOf(
            InlineKeyboardButton.builder().text("◀️ К списку").callbackData("tickets_list").build()
        ))

        editMessage(chatId, messageId, sb.toString().take(4096), InlineKeyboardMarkup.builder().keyboard(buttons).build())
    }

    // ── Message history delivery (on ticket take) ────────────────────────────

    private fun sendMessageHistory(adminChatId: Long, ticketId: Long) {
        val messages = ticketService.getMessages(ticketId)
        if (messages.isEmpty()) return

        // Text summary
        val sb = StringBuilder("📋 История сообщений:\n\n")
        messages.forEach { msg ->
            val time = msg.sentAt.atZone(tz).format(timeFmt)
            val icon = if (msg.senderType == SenderType.USER) "👤" else "🛠"
            if (msg.mediaType != null) {
                val label = "${msg.mediaType!!.displayName()} #${msg.mediaIndex}"
                val captionPart = if (msg.text.isNotEmpty()) " — ${msg.text}" else ""
                sb.append("[$time] $icon ${msg.senderName}: [$label$captionPart]\n\n")
            } else {
                sb.append("[$time] $icon ${msg.senderName}:\n${msg.text}\n\n")
            }
        }
        sendText(adminChatId, sb.toString().trimEnd().take(4096))

        // Forward each media file
        messages.filter { it.mediaType != null && it.fileId != null }.forEach { msg ->
            sendMedia(adminChatId, msg.fileId!!, msg.mediaType!!, mediaCaption(msg, ticketId))
        }
    }

    // ── Admin notifications ──────────────────────────────────────────────────

    private fun notifyAdmins(ticket: Ticket, previewText: String) {
        val text = "🆕 Новое обращение #${ticket.id}\nОт: ${ticket.userName}\n\n$previewText"
        val keyboard = buildTakeKeyboard(ticket.id)
        for (adminId in adminIds) {
            try {
                val sent = execute(SendMessage.builder().chatId(adminId).text(text).replyMarkup(keyboard).build())
                ticketService.recordAdminNotification(ticket.id, adminId, sent.messageId)
            } catch (e: Exception) {
                log.warn("Could not notify admin {}: {}", adminId, e.message)
            }
        }
    }

    private fun renotifyAdmins(ticketId: Long) {
        val ticket = ticketService.getTicketById(ticketId) ?: return
        val text = "🔄 Обращение #${ticket.id} снова открыто\nОт: ${ticket.userName}"
        val keyboard = buildTakeKeyboard(ticket.id)
        for (adminId in adminIds) {
            try {
                val sent = execute(SendMessage.builder().chatId(adminId).text(text).replyMarkup(keyboard).build())
                ticketService.recordAdminNotification(ticket.id, adminId, sent.messageId)
            } catch (e: Exception) {
                log.warn("Could not re-notify admin {}: {}", adminId, e.message)
            }
        }
    }

    private fun removeTicketButton(
        adminChatId: Long, messageId: Int, ticketId: Long,
        takenByOther: Boolean = false, takenByName: String? = null
    ) {
        val suffix = when {
            takenByName != null -> "\n\n⚠️ Обращение взял $takenByName."
            takenByOther        -> "\n\n⚠️ Обращение взято другим оператором."
            else                -> "\n\n✅ Вы взяли это обращение."
        }
        try {
            execute(EditMessageText.builder().chatId(adminChatId).messageId(messageId)
                .text("Обращение #$ticketId — взято.$suffix").build())
        } catch (e: Exception) {
            log.debug("Could not edit admin notification {}/{}: {}", adminChatId, messageId, e.message)
        }
    }

    // ── Media sending ────────────────────────────────────────────────────────

    private fun sendMedia(chatId: Long, fileId: String, mediaType: MediaType, caption: String) {
        val file = InputFile(fileId)
        try {
            when (mediaType) {
                MediaType.VOICE      -> execute(SendVoice.builder().chatId(chatId).voice(file).caption(caption).build())
                MediaType.PHOTO      -> execute(SendPhoto.builder().chatId(chatId).photo(file).caption(caption).build())
                MediaType.DOCUMENT   -> execute(SendDocument.builder().chatId(chatId).document(file).caption(caption).build())
                MediaType.VIDEO      -> execute(SendVideo.builder().chatId(chatId).video(file).caption(caption).build())
                MediaType.AUDIO      -> execute(SendAudio.builder().chatId(chatId).audio(file).caption(caption).build())
                MediaType.VIDEO_NOTE -> {
                    // VideoNote doesn't support captions — send label as a separate message
                    execute(SendVideoNote.builder().chatId(chatId).videoNote(file).build())
                    sendText(chatId, caption)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to send {} to {}: {}", mediaType, chatId, e.message)
        }
    }

    /** Caption attached to a media when forwarding in real-time or delivering history. */
    private fun mediaCaption(msg: TicketMessage, ticketId: Long, forUser: Boolean = false, adminName: String? = null): String {
        val label = "${msg.mediaType!!.displayName()} #${msg.mediaIndex} [#$ticketId]"
        return if (msg.text.isNotEmpty()) "$label — ${msg.text}" else label
    }

    // ── Low-level helpers ────────────────────────────────────────────────────

    private fun editMessage(chatId: Long, messageId: Int, text: String, keyboard: InlineKeyboardMarkup) {
        try {
            execute(EditMessageText.builder().chatId(chatId).messageId(messageId).text(text).replyMarkup(keyboard).build())
        } catch (e: Exception) {
            log.warn("Failed to edit message {}/{}: {}", chatId, messageId, e.message)
        }
    }

    private fun sendText(chatId: Long, text: String) {
        try {
            execute(SendMessage.builder().chatId(chatId).text(text).build())
        } catch (e: Exception) {
            log.warn("Failed to send message to {}: {}", chatId, e.message)
        }
    }

    private fun answerCallback(callbackId: String, text: String) {
        try {
            execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).text(text).build())
        } catch (e: Exception) {
            log.debug("Failed to answer callback: {}", e.message)
        }
    }

    private fun buildTakeKeyboard(ticketId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(
                InlineKeyboardButton.builder()
                    .text("✋ Взять обращение").callbackData("take_ticket:$ticketId").build()
            ))
            .build()

    private fun backKeyboard(): InlineKeyboardMarkup =
        InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(
                InlineKeyboardButton.builder()
                    .text("◀️ К списку").callbackData("tickets_list").build()
            ))
            .build()

    private fun buildUserName(user: User): String {
        val fullName = listOfNotNull(user.firstName, user.lastName).joinToString(" ")
        return if (user.userName != null) "$fullName (@${user.userName})" else fullName
    }

    private fun TicketStatus.icon() = when (this) {
        TicketStatus.OPEN        -> "🟡"
        TicketStatus.IN_PROGRESS -> "🟢"
        TicketStatus.CLOSED      -> "⚫"
    }
}
