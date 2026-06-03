package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Договор — специализация Document (type=CONTRACT).
 *
 * Основание платежа за услуги. Reconciler сверяет MoneyOperation ↔ Contract
 * по client_id + counterparty_id. signing_status определяет валидность основания
 * (SIGNED_BOTH_SIDES — ок, DRAFT — флаг). valid_from/valid_to — для EXPIRED.
 *
 * См. DEC-023 Коммит 4.
 */
@Audited
@Entity
@Table(name = "contracts",
    indexes = {
        @Index(name = "idx_contracts_document", columnList = "document_id"),
        @Index(name = "idx_contracts_client", columnList = "client_id"),
        @Index(name = "idx_contracts_counterparty", columnList = "counterparty_id"),
        @Index(name = "idx_contracts_number", columnList = "contract_number"),
        @Index(name = "idx_contracts_signing_status", columnList = "signing_status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_contracts_document", columnNames = {"document_id"})
    }
)
public class Contract {

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

    @Column(name = "contract_number", length = 100)
    private String contractNumber;

    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "subject_category", length = 50)
    private String subjectCategory;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

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

    public String getContractNumber() { return contractNumber; }
    public void setContractNumber(String contractNumber) { this.contractNumber = contractNumber; }

    public LocalDate getContractDate() { return contractDate; }
    public void setContractDate(LocalDate contractDate) { this.contractDate = contractDate; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getSubjectCategory() { return subjectCategory; }
    public void setSubjectCategory(String subjectCategory) { this.subjectCategory = subjectCategory; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

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
