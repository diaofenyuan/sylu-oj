package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedTicketRepository extends JpaRepository<ConsumedTicket, Long> {

    boolean existsByTicketHash(String ticketHash);
}
