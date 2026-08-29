package oj.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 已消费教务票据：仅存哈希，唯一约束拒绝重放；保留期覆盖票据最大有效期。
 */
@Entity
@Table(name = "consumed_ticket")
public class ConsumedTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_hash", nullable = false, unique = true, length = 64)
    private String ticketHash;

    @Column(name = "consumed_at", nullable = false)
    private LocalDateTime consumedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected ConsumedTicket() {
    }

    public ConsumedTicket(String ticketHash, LocalDateTime consumedAt, LocalDateTime expiresAt) {
        this.ticketHash = ticketHash;
        this.consumedAt = consumedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getTicketHash() {
        return ticketHash;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
