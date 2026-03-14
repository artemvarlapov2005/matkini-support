package org.ledger.model

import jakarta.persistence.*

enum class TicketStatus { OPEN, IN_PROGRESS, CLOSED }

@Entity
@Table(name = "tickets")
class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    var userChatId: Long = 0
    var userName: String = ""
    var adminChatId: Long? = null
    var adminName: String? = null

    @Enumerated(EnumType.STRING)
    var status: TicketStatus = TicketStatus.OPEN

    /**
     * Stores the Telegram message ID of the ticket notification sent to each admin.
     * Used to update/remove the "Take" button after the ticket is claimed.
     * adminChatId -> messageId
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "admin_notifications",
        joinColumns = [JoinColumn(name = "ticket_id")]
    )
    @MapKeyColumn(name = "admin_chat_id")
    @Column(name = "message_id")
    var adminNotificationMessages: MutableMap<Long, Int> = mutableMapOf()
}
