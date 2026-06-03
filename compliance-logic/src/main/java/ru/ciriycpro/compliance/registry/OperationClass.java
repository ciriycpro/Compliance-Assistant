package ru.ciriycpro.compliance.registry;

/**
 * Класс операции — детерминированный пре-классификатор (этап 1 экстрактора).
 * Гейтит сверку: в Reconciler заходят только COUNTERPARTY. Ортогонален parsed_subject_category. DEC-023.
 */
public enum OperationClass {
    OWN_TRANSFER, TAX, BANK_FEE, CARD_ACQUIRING, COUNTERPARTY
}
