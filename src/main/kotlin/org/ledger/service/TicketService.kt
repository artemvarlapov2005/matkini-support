package org.ledger.service

import jakarta.persistence.LockModeType
import org.ledger.model.MediaType
import org.ledger.model.SenderType
import org.ledger.model.Ticket
import org.ledger.model.TicketMessage
import org.ledger.model.TicketStatus
import org.ledger.repository.TicketMessageRepository
import org.ledger.repository.TicketRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TicketService(
    private val ticketRepository: TicketRepository,
    private val ticketMessageRepository: TicketMessageRepository
) {

    @Transactional
    fun createTicket(userChatId: Long, userName: String): Ticket {
        val ticket = Ticket().apply {
            this.userChatId = userChatId
            this.userName = userName
        }
        return ticketRepository.save(ticket)
    }

    fun getActiveTicketByUser(userChatId: Long): Ticket? =
        ticketRepository.findByUserChatIdAndStatusIn(
            userChatId,
            listOf(TicketStatus.OPEN, TicketStatus.IN_PROGRESS)
        )

    fun getActiveTicketByAdmin(adminChatId: Long): Ticket? =
        ticketRepository.findByAdminChatIdAndStatus(adminChatId, TicketStatus.IN_PROGRESS)

    fun getTicketById(id: Long): Ticket? = ticketRepository.findByIdOrNull(id)

    @Transactional
    fun recordAdminNotification(ticketId: Long, adminChatId: Long, messageId: Int) {
        ticketRepository.findByIdOrNull(ticketId)?.let { ticket ->
            ticket.adminNotificationMessages[adminChatId] = messageId
        }
    }

    /**
     * Assigns a ticket to an admin atomically.
     * Returns false if the ticket is already taken or doesn't exist.
     */
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun assignAdmin(ticketId: Long, adminChatId: Long, adminName: String): Boolean {
        val ticket = ticketRepository.findByIdOrNull(ticketId) ?: return false
        if (ticket.status != TicketStatus.OPEN) return false
        ticket.adminChatId = adminChatId
        ticket.adminName = adminName
        ticket.status = TicketStatus.IN_PROGRESS
        return true
    }

    /**
     * Releases a ticket back to OPEN without closing it.
     * Returns the ticket so the caller can re-notify admins.
     */
    @Transactional
    fun leaveTicket(ticketId: Long): Ticket? {
        val ticket = ticketRepository.findByIdOrNull(ticketId) ?: return null
        ticket.adminChatId = null
        ticket.adminName = null
        ticket.status = TicketStatus.OPEN
        ticket.adminNotificationMessages.clear()
        return ticket
    }

    @Transactional
    fun closeTicket(ticketId: Long) {
        ticketRepository.findByIdOrNull(ticketId)?.let { ticket ->
            ticket.status = TicketStatus.CLOSED
        }
    }

    fun getOpenTickets(): List<Ticket> = ticketRepository.findByStatus(TicketStatus.OPEN)

    fun getAllTickets(): List<Ticket> = ticketRepository.findAllByOrderByIdDesc()

    @Transactional
    fun saveMessage(
        ticketId: Long,
        text: String,
        senderType: SenderType,
        senderName: String,
        mediaType: MediaType? = null,
        fileId: String? = null
    ): TicketMessage {
        val mediaIndex = if (mediaType != null) {
            ticketMessageRepository.countByTicketIdAndMediaTypeIsNotNull(ticketId) + 1
        } else null

        val message = TicketMessage().apply {
            this.ticketId = ticketId
            this.text = text
            this.senderType = senderType
            this.senderName = senderName
            this.mediaType = mediaType
            this.fileId = fileId
            this.mediaIndex = mediaIndex
        }
        return ticketMessageRepository.save(message)
    }

    fun getMessages(ticketId: Long): List<TicketMessage> =
        ticketMessageRepository.findByTicketIdOrderBySentAtAsc(ticketId)
}
