const express = require('express');
const nodemailer = require('nodemailer');
const TelegramBot = require('node-telegram-bot-api');

const TG_TOKEN = '8755820807:AAEw-egtDGxe_LXu9zvzsmPh8cPmqvKjN34';
const TG_CHAT_ID_ARTEM = 249979054;
const TG_CHAT_ID_TAIROV = 1257818936;
const SMTP_USER = '5458508@mail.ru';
const SMTP_PASS = 'URlH1X5kBkanVvw23erl';
const WA_NUMBER = '79266143959';

const app = express();
app.use(express.json());

const tg = new TelegramBot(TG_TOKEN, { polling: true });

const ORCHESTRATOR_URL = process.env.ORCHESTRATOR_URL || 'http://127.0.0.1:8769';
const ORCHESTRATOR_API_KEY = process.env.ORCHESTRATOR_API_KEY || '';

// Reply keyboard — постоянно висит внизу чата как часть UI
// === ux_timers: per-chat_id timer registry (DEC-013 push-pattern) ===
// Используется для очистки таймеров при ошибках запуска workflow.
// В v1.2.2 слепые setTimeout убраны — прогресс-сообщения теперь приходят как events
// из orchestrator через /workflow-progress.
const uxTimers = new Map();

function clearUxTimers(chatId) {
    const timers = uxTimers.get(chatId);
    if (!timers) return false;
    timers.forEach(t => {
        if (t) clearTimeout(t);
    });
    uxTimers.delete(chatId);
    return true;
}

const persistentKeyboard = {
    reply_markup: {
        keyboard: [[{ text: '🔍 Проверить почту' }]],
        resize_keyboard: true,
        is_persistent: true
    }
};

const mailer = nodemailer.createTransport({
    host: 'smtp.mail.ru',
    port: 465,
    secure: true,
    auth: { user: SMTP_USER, pass: SMTP_PASS }
});

async function sendWhatsApp(number, text) {
    const { Client, LocalAuth } = require('whatsapp-web.js');
    const wa = new Client({
        authStrategy: new LocalAuth(),
        puppeteer: { args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'] }
    });
    await new Promise((resolve, reject) => {
        wa.on('ready', resolve);
        wa.on('auth_failure', reject);
        wa.initialize();
    });
    const r = await wa.sendMessage(number.replace(/\D/g, '') + '@c.us', text);
    console.log('WA → отправлено, ждём 60 сек...');
    await new Promise(r => setTimeout(r, 60000));
    await wa.destroy();
    console.log('WA → Chrome убит');
    return r;
}

app.get('/status', (req, res) => res.json({ ok: true }));

app.post('/send-tg', async (req, res) => {
    const { chat_id, text } = req.body;
    try {
        const r = await tg.sendMessage(chat_id, text);
        console.log('TG →', chat_id, ':', text.slice(0, 40));
        res.json({ ok: true, id: r.message_id });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/send-email', async (req, res) => {
    const { to, subject, text } = req.body;
    try {
        const r = await mailer.sendMail({ from: SMTP_USER, to, cc: '3088377@mail.ru', subject, text });
        console.log('EMAIL →', to, ':', subject);
        res.json({ ok: true, id: r.messageId });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/send-wa', async (req, res) => {
    const { number, text } = req.body;
    console.log('WA → запускаем Chrome...');
    try {
        const r = await sendWhatsApp(number || WA_NUMBER, text);
        res.json({ ok: true, id: r.id._serialized });
    } catch (e) {
        console.log('WA ошибка:', e.message);
        res.status(500).json({ error: e.message });
    }
});

app.post('/broadcast', async (req, res) => {
    const { text, channels, tg_chat_id } = req.body;
    const results = {};
    if (channels.includes('tg')) {
        try {
            await tg.sendMessage(tg_chat_id || TG_CHAT_ID_TAIROV, text);
            results.tg = 'ok';
        } catch (e) { results.tg = 'err: ' + e.message; }
    }
    if (channels.includes('email')) {
        try {
            await mailer.sendMail({ from: SMTP_USER, to: 'tgk-777@mail.ru', cc: '3088377@mail.ru', subject: 'Уведомление от Caller', text });
            results.email = 'ok';
        } catch (e) { results.email = 'err: ' + e.message; }
    }
    if (channels.includes('wa')) {
        try {
            await sendWhatsApp(WA_NUMBER, text);
            results.wa = 'ok';
        } catch (e) { results.wa = 'err: ' + e.message; }
    }
    console.log('BROADCAST:', results);
    res.json(results);
});

// Reply keyboard работает как текстовое сообщение — ловим по тексту
tg.on('message', async (msg) => {
    if (msg.text === '🔍 Проверить почту') {
        const chatId = msg.chat.id;
        console.log('TG button pressed by', chatId);
        await tg.sendMessage(chatId, '🔄 Проверяю почту, сейчас пришлю сводку.');

        // UX-таймеры: бот сам пишет промежуточные сообщения если workflow долгий.
        // Реальное время на 9 файлов / 14 МБ деловой почты = ~9 минут (vision на сканах).
        // Если workflow завершится раньше — сообщения всё равно отправятся, не страшно.
        // v1.2.2: слепые setTimeout убраны. Прогресс-сообщения приходят как events
        // из orchestrator через /workflow-progress. Guard-таймер очищает Map через 11 минут
        // на случай если что-то пошло не так и orchestrator не пушнул /workflow-done.
        const tGuard = setTimeout(() => {
            clearUxTimers(chatId);
        }, 660_000);

        // Регистрируем guard в Map (для возможной отмены при ошибке запуска workflow)
        uxTimers.set(chatId, [tGuard]);

        try {
            const r = await fetch(ORCHESTRATOR_URL + '/digest-now', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-API-Key': ORCHESTRATOR_API_KEY,
                },
                body: JSON.stringify({ period_hours: 24 }),
            });
            if (!r.ok) {
                clearUxTimers(chatId);
                const errText = await r.text();
                console.error('Orchestrator error:', r.status, errText);
                await tg.sendMessage(chatId, '⚠️ Ошибка запуска проверки: ' + r.status);
                return;
            }
            const data = await r.json();
            console.log('Orchestrator trace_id:', data.trace_id);
        } catch (e) {
            clearUxTimers(chatId);
            console.error('Orchestrator call failed:', e.message);
            await tg.sendMessage(chatId, '⚠️ Не смог запустить проверку: ' + e.message);
        }
    }
});

// === workflow-progress endpoint ===
// Orchestrator пушит сюда прогресс-события (event-driven UX).
// Мы по step + meta решаем что показать пользователю.
//
// Текущие пороги:
//   step=attachments_start + meta.count >= 3   → "📚 Тут немало документов..."
//   step=summary_start    + meta.elapsed_ms >= 60000 → "⏳ Почти готово..."
//
// Пороги можно менять здесь без правки orchestrator.
app.post('/workflow-progress', async (req, res) => {
    const { chat_id, trace_id, step, meta } = req.body;
    if (!chat_id || !step) {
        return res.status(400).json({ error: 'chat_id and step required' });
    }
    const chatIdNum = parseInt(chat_id, 10);
    const m = meta || {};

    let message = null;

    if (step === 'attachments_start' && (m.count || 0) >= 3) {
        message = '📚 Тут немало документов, разбираюсь. Подожди ещё пару минут.';
    } else if (step === 'summary_start' && (m.elapsed_ms || 0) >= 60000) {
        message = '⏳ Почти готово, формирую обзор.';
    }

    if (message) {
        try {
            await tg.sendMessage(chatIdNum, message);
            console.log(`workflow-progress: chat_id=${chat_id} step=${step} → SENT trace_id=${trace_id}`);
            return res.json({ ok: true, sent: true, message });
        } catch (e) {
            console.error('progress send failed:', e.message);
            return res.status(500).json({ ok: false, error: e.message });
        }
    }

    // Step не требует сообщения по текущим порогам
    console.log(`workflow-progress: chat_id=${chat_id} step=${step} meta=${JSON.stringify(m)} → skipped trace_id=${trace_id}`);
    res.json({ ok: true, sent: false, reason: 'thresholds_not_met' });
});

// === workflow-done endpoint ===
// Orchestrator пушит сюда когда workflow завершён (delivered / no_messages / failed / lock_held).
// Мы очищаем UX-таймеры этого chat_id.
//
// При status="lock_held" — НЕ очищаем таймеры (это второй кликер,
// первый workflow ещё работает и его progress events должны приходить).
app.post('/workflow-done', async (req, res) => {
    const { chat_id, trace_id, status } = req.body;
    if (!chat_id) {
        return res.status(400).json({ error: 'chat_id required' });
    }
    const chatIdNum = parseInt(chat_id, 10);

    if (status === 'lock_held') {
        // Не очищаем — первый workflow продолжается
        console.log(`workflow-done lock_held: chat_id=${chat_id} trace_id=${trace_id} (НЕ чистим таймеры)`);
        try {
            await tg.sendMessage(chatIdNum, '⏳ Уже обрабатываю предыдущий запрос, скоро пришлю.');
        } catch (e) {
            console.error('lock_held notify send failed:', e.message);
        }
        return res.json({ ok: true, status: 'lock_held', cleared: false });
    }

    const cleared = clearUxTimers(chatIdNum);
    console.log(`workflow-done: chat_id=${chat_id} status=${status} trace_id=${trace_id} cleared=${cleared}`);
    res.json({ ok: true, status, cleared });
});

// Endpoint для активации кнопки у пользователя
app.post('/tg/setup-button', async (req, res) => {
    const { chat_id, intro_text } = req.body;
    const text = intro_text || 'Готов к работе. Кнопка для проверки почты внизу 👇';
    try {
        const r = await tg.sendMessage(chat_id, text, persistentKeyboard);
        console.log('TG setup-button →', chat_id);
        res.json({ ok: true, id: r.message_id });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

console.log('Telegram polling started, reply keyboard ready');

app.listen(3000, '127.0.0.1', () => console.log('HTTP :3000 (WA по требованию)'));
