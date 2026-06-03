package ru.ciriycpro.compliance.service;

/**
 * Порт транспорта к Agent Caller. Реализация скрыта (HTTP сейчас, /dispatch/Business API потом).
 * compliance-logic про кишки Caller не знает — только этот контракт.
 */
public interface CallerPort {

    /** Отправить сообщение в Telegram. Возвращает сырой JSON-ответ Caller. Бросает при ошибке. */
    String sendTelegram(long chatId, String text);

    /** Отправить сообщение в WhatsApp на номер (цифры). Возвращает сырой JSON-ответ Caller. Бросает при ошибке. */
    String sendWhatsApp(String number, String text);
}
