package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Акт — специализация Document (type=ACT). Подтверждает исполнение по договору.
 * Связан с Contract (nullable — акт мог прийти до сопоставления). Reconciler
 * проверяет UNSIGNED_ACT и наличие акта под операцией. См. DEC-023 Коммит 4.
 */
@Audited
@Entity
@Table(name = "acts",
    indexes = {
        @Index(name = "idx_acts_document", columnList = "document_id"),
        @Index(name = "idx_acts_client", columnList = "client_id"),
        @Index(name = "idx_acts_counterparty", columnList = "counterparty_id"),
        @Index(name = "idx_acts_contract", columnList = "contract_id"),
        @Index(name = "idx_acts_number", columnList = "act_number"),
        @Index(name = "idx_acts_signing_status", columnList = "signing_status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_acts_document", columnNames = {"document_id"})
    }
)
public class Act {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private Counterparty counterparty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id")
    private Contract contract;

    @Column(name = "act_number", length = 100)
    private String actNumber;

    @Column(name = "act_date")
    private LocalDate actDate;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "subject", length = 500)
    private String subject;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "signing_status", nullable = false, length = 20)
    private SigningStatus signingStatus = SigningStatus.DRAFT;

    @Column(name = "client_signed", nullable = false)
    private boolean clientSigned = false;

    @Column(name = "client_signed_date")
    private LocalDate clientSignedDate;

    @Column(name = "counterparty_signed", nullable = false)
    private boolean counterpartySigned = false;

    @Column(name = "counterparty_signed_date")
    private LocalDate counterpartySignedDate;

    @Column(name = "signature_confidence", precision = 3, scale = 2)
    private BigDecimal signatureConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Counterparty getCounterparty() { return counterparty; }
    public void setCounterparty(Counterparty counterparty) { this.counterparty = counterparty; }

    public Contract getContract() { return contract; }
    public void setContract(Contract contract) { this.contract = contract; }

    public String getActNumber() { return actNumber; }
    public void setActNumber(String actNumber) { this.actNumber = actNumber; }

    public LocalDate getActDate() { return actDate; }
    public void setActDate(LocalDate actDate) { this.actDate = actDate; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public SigningStatus getSigningStatus() { return signingStatus; }
    public void setSigningStatus(SigningStatus signingStatus) { this.signingStatus = signingStatus; }

    public boolean isClientSigned() { return clientSigned; }
    public void setClientSigned(boolean clientSigned) { this.clientSigned = clientSigned; }

    public LocalDate getClientSignedDate() { return clientSignedDate; }
    public void setClientSignedDate(LocalDate clientSignedDate) { this.clientSignedDate = clientSignedDate; }

    public boolean isCounterpartySigned() { return counterpartySigned; }
    public void setCounterpartySigned(boolean counterpartySigned) { this.counterpartySigned = counterpartySigned; }

    public LocalDate getCounterpartySignedDate() { return counterpartySignedDate; }
    public void setCounterpartySignedDate(LocalDate counterpartySignedDate) { this.counterpartySignedDate = counterpartySignedDate; }

    public BigDecimal getSignatureConfidence() { return signatureConfidence; }
    public void setSignatureConfidence(BigDecimal signatureConfidence) { this.signatureConfidence = signatureConfidence; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
