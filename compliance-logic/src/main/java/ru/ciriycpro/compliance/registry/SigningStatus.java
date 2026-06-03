package ru.ciriycpro.compliance.registry;

/**
 * Статус подписания договора/акта. См. DEC-023 Коммит 4.
 */
public enum SigningStatus {
    DRAFT,             // только проект, не подписан
    SIGNED_ONE_SIDE,   // подписан одной стороной
    SIGNED_BOTH_SIDES, // подписан обеими сторонами — валидное основание
    UNCLEAR,           // не удалось определить (vision-LLM low confidence)
    DISPUTED           // спорный/оспаривается
}
