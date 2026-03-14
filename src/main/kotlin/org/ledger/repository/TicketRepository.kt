package org.ledger.repository

import org.ledger.model.Ticket
import org.ledger.model.TicketStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TicketRepository : JpaRepository<Ticket, Long> {

    fun findByUserChatIdAndStatusIn(userChatId: Long, statuses: List<TicketStatus>): Ticket?

    fun findByAdminChatIdAndStatus(adminChatId: Long, status: TicketStatus): Ticket?

    fun findByStatus(status: TicketStatus): List<Ticket>

    fun findAllByOrderByIdDesc(): List<Ticket>
}
