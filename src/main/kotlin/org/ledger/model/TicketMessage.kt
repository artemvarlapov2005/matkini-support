package org.ledger.model

import jakarta.persistence.*
import java.time.Instant

enum class SenderType { USER, ADMIN }

enum class MediaType {
    VOICE, PHOTO, DOCUMENT, VIDEO, AUDIO, VIDEO_NOTE;

    fun displayName() = when (this) {
        VOICE      -> "Голосовое"
        PHOTO      -> "Фото"
        DOCUMENT   -> "Файл"
        VIDEO      -> "Видео"
        AUDIO      -> "Аудио"
        VIDEO_NOTE -> "Видеосообщение"
    }
}

@Entity
@Table(name = "ticket_messages")
class TicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    var ticketId: Long = 0

    // Text content or caption; empty string for media without caption
    @Column(columnDefinition = "TEXT")
    var text: String = ""

    @Enumerated(EnumType.STRING)
    var senderType: SenderType = SenderType.USER

    var senderName: String = ""

    @Enumerated(EnumType.STRING)
    var mediaType: MediaType? = null

    // Telegram file_id — used to re-send the file without storing it
    var fileId: String? = null

    // Sequential number of this media within the ticket (1-based)
    var mediaIndex: Int? = null

    var sentAt: Instant = Instant.now()
}
