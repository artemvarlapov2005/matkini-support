package org.ledger.repository

import org.ledger.model.TicketMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TicketMessageRepository : JpaRepository<TicketMessage, Long> {

    fun findByTicketIdOrderBySentAtAsc(ticketId: Long): List<TicketMessage>

    fun countByTicketIdAndMediaTypeIsNotNull(ticketId: Long): Int
}
