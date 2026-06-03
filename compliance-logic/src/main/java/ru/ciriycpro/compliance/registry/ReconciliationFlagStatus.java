package ru.ciriycpro.compliance.registry;

/** Жизненный цикл флага: обнаружен -> запрос отправлен -> закрыт. См. DEC-023. */
public enum ReconciliationFlagStatus {
    DETECTED,      // Reconciler нашёл проблему
    REQUEST_SENT,  // напоминание отправлено клиенту (Коммит 5/6, Agent Caller)
    RESOLVED       // договор появился -> авто-закрытие на re-scan
}
