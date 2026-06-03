package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Операция (строка) из банковской выписки.
 *
 * Структура парсинга:
 *  - purpose: сырое назначение платежа из выписки
 *  - counterparty_inn_raw / counterparty_name_raw: ИНН и название контрагента,
 *    извлечённые парсером выписки. Matching на Counterparty entity делает Reconciler.
 *  - parsed_*: результаты LLM/regex парсинга назначения платежа.
 *    Заполняются позже, не обязательны при создании MoneyOperation.
 *  - linked_contract_id / linked_act_id: связь с Contract/Act после matching через Reconciler v2.0.
 *
 * См. DEC-023 Registry v1.0.
 */
@Audited
@Entity
@Table(name = "money_operations",
    indexes = {
        @Index(name = "idx_money_ops_statement", columnList = "statement_id"),
        @Index(name = "idx_money_ops_client", columnList = "client_id"),
        @Index(name = "idx_money_ops_counterparty", columnList = "counterparty_id"),
        @Index(name = "idx_money_ops_date", columnList = "operation_date"),
        @Index(name = "idx_money_ops_direction", columnList = "direction"),
        @Index(name = "idx_money_ops_linked_contract", columnList = "linked_contract_id"),
        @Index(name = "idx_money_ops_parsed_contract", columnList = "parsed_contract_number"),
        @Index(name = "idx_money_ops_parsed_category", columnList = "parsed_subject_category"),
        @Index(name = "idx_money_ops_operation_class", columnList = "operation_class")
    }
)
public class MoneyOperation {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id")
    private Counterparty counterparty;

    @NotNull
    @Column(name = "operation_date", nullable = false)
    private LocalDate operationDate;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private OperationDirection direction;

    @Column(name = "purpose", length = 2000)
    private String purpose;

    @Column(name = "counterparty_inn_raw", length = 12)
    private String counterpartyInnRaw;

    @Column(name = "counterparty_name_raw", length = 500)
    private String counterpartyNameRaw;

    @Column(name = "parsed_contract_number", length = 100)
    private String parsedContractNumber;

    @Column(name = "parsed_contract_date")
    private LocalDate parsedContractDate;

    @Column(name = "parsed_invoice_number", length = 100)
    private String parsedInvoiceNumber;

    @Column(name = "parsed_subject", length = 500)
    private String parsedSubject;

    @Column(name = "parsed_subject_category", length = 50)
    private String parsedSubjectCategory;

    @Column(name = "parsed_quantity", precision = 18, scale = 3)
    private BigDecimal parsedQuantity;

    @Column(name = "parsed_unit", length = 20)
    private String parsedUnit;

    @Column(name = "parsed_vat_amount", precision = 18, scale = 2)
    private BigDecimal parsedVatAmount;

    @Column(name = "parsing_confidence", precision = 3, scale = 2)
    private BigDecimal parsingConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_class", length = 20)
    private OperationClass operationClass;

    @Column(name = "linked_contract_id", columnDefinition = "uuid")
    private UUID linkedContractId;

    @Column(name = "linked_act_id", columnDefinition = "uuid")
    private UUID linkedActId;

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

    public Statement getStatement() { return statement; }
    public void setStatement(Statement statement) { this.statement = statement; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Counterparty getCounterparty() { return counterparty; }
    public void setCounterparty(Counterparty counterparty) { this.counterparty = counterparty; }

    public LocalDate getOperationDate() { return operationDate; }
    public void setOperationDate(LocalDate operationDate) { this.operationDate = operationDate; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public OperationDirection getDirection() { return direction; }
    public void setDirection(OperationDirection direction) { this.direction = direction; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getCounterpartyInnRaw() { return counterpartyInnRaw; }
    public void setCounterpartyInnRaw(String counterpartyInnRaw) { this.counterpartyInnRaw = counterpartyInnRaw; }

    public String getCounterpartyNameRaw() { return counterpartyNameRaw; }
    public void setCounterpartyNameRaw(String counterpartyNameRaw) { this.counterpartyNameRaw = counterpartyNameRaw; }

    public String getParsedContractNumber() { return parsedContractNumber; }
    public void setParsedContractNumber(String parsedContractNumber) { this.parsedContractNumber = parsedContractNumber; }

    public LocalDate getParsedContractDate() { return parsedContractDate; }
    public void setParsedContractDate(LocalDate parsedContractDate) { this.parsedContractDate = parsedContractDate; }

    public String getParsedInvoiceNumber() { return parsedInvoiceNumber; }
    public void setParsedInvoiceNumber(String parsedInvoiceNumber) { this.parsedInvoiceNumber = parsedInvoiceNumber; }

    public String getParsedSubject() { return parsedSubject; }
    public void setParsedSubject(String parsedSubject) { this.parsedSubject = parsedSubject; }

    public String getParsedSubjectCategory() { return parsedSubjectCategory; }
    public void setParsedSubjectCategory(String parsedSubjectCategory) { this.parsedSubjectCategory = parsedSubjectCategory; }

    public BigDecimal getParsedQuantity() { return parsedQuantity; }
    public void setParsedQuantity(BigDecimal parsedQuantity) { this.parsedQuantity = parsedQuantity; }

    public String getParsedUnit() { return parsedUnit; }
    public void setParsedUnit(String parsedUnit) { this.parsedUnit = parsedUnit; }

    public BigDecimal getParsedVatAmount() { return parsedVatAmount; }
    public void setParsedVatAmount(BigDecimal parsedVatAmount) { this.parsedVatAmount = parsedVatAmount; }

    public BigDecimal getParsingConfidence() { return parsingConfidence; }
    public void setParsingConfidence(BigDecimal parsingConfidence) { this.parsingConfidence = parsingConfidence; }

    public OperationClass getOperationClass() { return operationClass; }
    public void setOperationClass(OperationClass operationClass) { this.operationClass = operationClass; }

    public UUID getLinkedContractId() { return linkedContractId; }
    public void setLinkedContractId(UUID linkedContractId) { this.linkedContractId = linkedContractId; }

    public UUID getLinkedActId() { return linkedActId; }
    public void setLinkedActId(UUID linkedActId) { this.linkedActId = linkedActId; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
