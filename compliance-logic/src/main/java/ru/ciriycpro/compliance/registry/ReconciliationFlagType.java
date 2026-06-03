package ru.ciriycpro.compliance.registry;

/** Тип флага сверки. v1 — MISSING_CONTRACT. См. DEC-023 Коммит 4. */
public enum ReconciliationFlagType {
    MISSING_CONTRACT   // по контрагенту есть платежи (COUNTERPARTY), но нет договора
}
