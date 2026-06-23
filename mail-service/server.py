import os
from fastapi import FastAPI, HTTPException
import imaplib
import email
from email.header import decode_header
from email.utils import parsedate_to_datetime
from datetime import datetime, timezone
import re
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger("mail-service")

app = FastAPI(title="Mail Service v1")

# === КОНФИГ ===
IMAP_HOST = os.getenv('IMAP_HOST', 'imap.mail.ru')
IMAP_PORT = int(os.getenv('IMAP_PORT', '993'))
IMAP_USER = os.getenv('IMAP_USER')
IMAP_PASS = os.getenv('IMAP_PASS')
DEFAULT_MAILBOX = 'INBOX'

# Multi-mailbox support (DEC-014 v1.1)
# Если MAILBOXES_JSON задан — используем список ящиков из него.
# Иначе — fallback к single-mailbox через IMAP_HOST/USER/PASS.
MAILBOXES_JSON = os.getenv('MAILBOXES_JSON', '')
MAILBOXES = []
if MAILBOXES_JSON:
    import json
    try:
        MAILBOXES = json.loads(MAILBOXES_JSON)
        log.info(f"Multi-mailbox mode: {len(MAILBOXES)} mailboxes configured")
        for m in MAILBOXES:
            log.info(f"  - {m.get('label', '?')}: {m.get('user', '?')} @ {m.get('host', '?')}")
    except json.JSONDecodeError as e:
        log.error(f"MAILBOXES_JSON parse error: {e}")
        MAILBOXES = []

# Если multi не задан — создаём один из IMAP_*
if not MAILBOXES and IMAP_USER and IMAP_PASS:
    MAILBOXES = [{
        'label': 'default',
        'host': IMAP_HOST,
        'port': IMAP_PORT,
        'user': IMAP_USER,
        'pass': IMAP_PASS,
        'mailbox': DEFAULT_MAILBOX,
    }]
    log.info(f"Single-mailbox mode (fallback): {IMAP_USER}")


def decode_str(s):
    if not s:
        return ''
    try:
        parts = decode_header(s)
        result = []
        for part, enc in parts:
            if isinstance(part, bytes):
                result.append(part.decode(enc or 'utf-8', errors='replace'))
            else:
                result.append(part)
        return ''.join(result).strip()
    except Exception as e:
        log.warning(f"decode_str failed: {e}")
        return str(s)


def extract_email(from_raw):
    if not from_raw:
        return ''
    match = re.search(r'<([^>]+)>', from_raw)
    if match:
        return match.group(1).strip()
    match = re.search(r'([\w.+-]+@[\w-]+\.[\w.-]+)', from_raw)
    return match.group(1).strip() if match else ''


def extract_fio(from_raw, email_addr):
    if not from_raw:
        return ''
    fio = re.sub(r'<[^>]+>', '', from_raw).replace('"', '').strip()
    if not fio or fio == email_addr:
        return email_addr.split('@')[0] if email_addr else ''
    return fio


def get_body_text(msg):
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == 'text/plain' and part.get_content_disposition() != 'attachment':
                payload = part.get_payload(decode=True)
                if payload:
                    charset = part.get_content_charset() or 'utf-8'
                    try:
                        return payload.decode(charset, errors='replace').strip()
                    except Exception:
                        return payload.decode('utf-8', errors='replace').strip()
        for part in msg.walk():
            if part.get_content_type() == 'text/html' and part.get_content_disposition() != 'attachment':
                payload = part.get_payload(decode=True)
                if payload:
                    charset = part.get_content_charset() or 'utf-8'
                    try:
                        html = payload.decode(charset, errors='replace')
                    except Exception:
                        html = payload.decode('utf-8', errors='replace')
                    text = re.sub(r'<[^>]+>', ' ', html)
                    text = re.sub(r'\s+', ' ', text).strip()
                    return text
        return ''
    else:
        payload = msg.get_payload(decode=True)
        if payload:
            charset = msg.get_content_charset() or 'utf-8'
            try:
                return payload.decode(charset, errors='replace').strip()
            except Exception:
                return payload.decode('utf-8', errors='replace').strip()
        return ''


def get_attachment_names(msg):
    names = []
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_disposition() == 'attachment':
                fn = part.get_filename()
                if fn:
                    names.append(decode_str(fn))
    return names


def parse_date(date_str):
    try:
        dt = parsedate_to_datetime(date_str)
        return dt.isoformat()
    except Exception:
        return date_str or ''


@app.get("/health")
def health():
    return {"status": "ok", "service": "mail-service-v1"}


def _fetch_from_mailbox(mb_config, dt, imap_date, mailbox_folder):
    """Получить письма из одного ящика. Возвращает список dict."""
    label = mb_config.get('label', 'unknown')
    host = mb_config['host']
    port = int(mb_config.get('port', 993))
    user = mb_config['user']
    password = mb_config['pass']

    log.info(f"[{label}] Connecting to {host}:{port} as {user}")
    result = []
    M = None
    try:
        M = imaplib.IMAP4_SSL(host, port)
        M.login(user, password)
        M.select(mailbox_folder)

        typ, data = M.search(None, f'SINCE {imap_date}')
        if typ != 'OK':
            log.warning(f"[{label}] IMAP search failed: {typ}")
            return []

        ids = data[0].decode().split()
        log.info(f"[{label}] Found {len(ids)} messages")

        for msg_id in ids:
            try:
                typ, msg_data = M.fetch(msg_id, '(BODY.PEEK[])')
                if typ != 'OK' or not msg_data or not msg_data[0]:
                    continue

                raw = msg_data[0][1]
                msg = email.message_from_bytes(raw)

                from_raw = decode_str(msg.get('From', ''))
                email_addr = extract_email(from_raw)
                fio = extract_fio(from_raw, email_addr)

                msg_date_str = parse_date(msg.get('Date', ''))
                if msg_date_str:
                    try:
                        msg_dt = datetime.fromisoformat(msg_date_str.replace('Z', '+00:00'))
                        if msg_dt.tzinfo is not None:
                            msg_dt = msg_dt.astimezone(timezone.utc).replace(tzinfo=None)
                        if msg_dt < dt:
                            continue
                    except (ValueError, AttributeError) as e:
                        log.warning(f"[{label}] Failed to parse msg date '{msg_date_str}': {e}")

                result.append({
                    "messageId": msg.get('Message-ID', '').strip('<> ').strip(),
                    "mailbox_label": label,
                    "mailbox_user": user,
                    "from": from_raw,
                    "email": email_addr,
                    "fio": fio,
                    "subject": decode_str(msg.get('Subject', '')),
                    "date": msg_date_str,
                    "body_text": get_body_text(msg)[:5000],
                    "attachment_names": get_attachment_names(msg),
                })
            except Exception as e:
                log.warning(f"[{label}] Failed to parse msg {msg_id}: {e}")
                continue
        return result
    except imaplib.IMAP4.error as e:
        log.error(f"[{label}] IMAP error: {e}")
        return []
    except Exception as e:
        log.error(f"[{label}] Unexpected error: {e}", exc_info=True)
        return []
    finally:
        if M:
            try:
                M.logout()
            except Exception:
                pass


@app.get("/mail/since/{since_date}")
def get_mail_since(since_date: str, mailbox: str = DEFAULT_MAILBOX, label: str = None, group: str = None):
    # Принимаем только YYYY-MM-DDTHH:MM (точность для оркестратора v1+).
    try:
        dt = datetime.strptime(since_date, '%Y-%m-%dT%H:%M')
        imap_date = dt.strftime('%d-%b-%Y')
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail="Date format must be YYYY-MM-DDTHH:MM (e.g. 2026-05-13T15:30)"
        )

    if not MAILBOXES:
        raise HTTPException(status_code=500, detail="No mailboxes configured")

    # Адресация тула (DEC-022): label=точный ящик, group=набор, иначе — только default-ящики
    # (без default:false). Так дайджест (без фильтра) НЕ тянет compliance-ящик,
    # а compliance зовёт ?label=... — каждый берёт своё, без замесов.
    if label:
        boxes = [mb for mb in MAILBOXES if mb.get("label") == label]
    elif group:
        boxes = [mb for mb in MAILBOXES if mb.get("group") == group]
    else:
        boxes = [mb for mb in MAILBOXES if mb.get("default", True)]

    if not boxes:
        log.info(f"No mailbox matched (label={label}, group={group}); configured={len(MAILBOXES)}")
        return []

    log.info(f"Fetching since {imap_date} from {len(boxes)}/{len(MAILBOXES)} mailbox(es) [label={label} group={group}]")

    all_messages = []
    for mb in boxes:
        messages = _fetch_from_mailbox(mb, dt, imap_date, mailbox)
        all_messages.extend(messages)

    # Сортируем по дате (новые сверху)
    def _date_key(m):
        try:
            return datetime.fromisoformat(m['date'].replace('Z', '+00:00'))
        except Exception:
            return datetime.min.replace(tzinfo=timezone.utc)
    all_messages.sort(key=_date_key, reverse=True)

    log.info(f"Total: {len(all_messages)} messages from {len(MAILBOXES)} mailbox(es)")
    return all_messages


# === Legacy функция (используется на уровне старого пути, если кому-то понадобится) ===
def _legacy_get_mail_single_mailbox(since_date: str, mailbox: str = DEFAULT_MAILBOX):
    # Оставляем для возможной обратной совместимости. Не используется в multi-mailbox flow.
    try:
        dt = datetime.strptime(since_date, '%Y-%m-%dT%H:%M')
        imap_date = dt.strftime('%d-%b-%Y')
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail="Date format must be YYYY-MM-DDTHH:MM (e.g. 2026-05-13T15:30)"
        )

    log.info(f"Fetching mail since {imap_date} from {mailbox}")
    
    M = None
    try:
        M = imaplib.IMAP4_SSL(IMAP_HOST, IMAP_PORT)
        M.login(IMAP_USER, IMAP_PASS)
        M.select(mailbox)

        typ, data = M.search(None, f'SINCE {imap_date}')
        if typ != 'OK':
            raise HTTPException(status_code=500, detail=f"IMAP search failed: {typ}")

        ids = data[0].decode().split()
        log.info(f"Found {len(ids)} messages")

        result = []
        for msg_id in ids:
            try:
                typ, msg_data = M.fetch(msg_id, '(BODY.PEEK[])')
                if typ != 'OK' or not msg_data or not msg_data[0]:
                    continue

                raw = msg_data[0][1]
                msg = email.message_from_bytes(raw)

                from_raw = decode_str(msg.get('From', ''))
                email_addr = extract_email(from_raw)
                fio = extract_fio(from_raw, email_addr)

                msg_date_str = parse_date(msg.get('Date', ''))
                # Пост-фильтрация по времени (IMAP отдаёт за день, нам нужно с HH:MM)
                if msg_date_str:
                    try:
                        # parse_date возвращает ISO 8601, может быть с TZ или без
                        msg_dt = datetime.fromisoformat(msg_date_str.replace('Z', '+00:00'))
                        # Приводим оба к naive UTC для сравнения
                        if msg_dt.tzinfo is not None:
                            msg_dt = msg_dt.astimezone(timezone.utc).replace(tzinfo=None)
                        if msg_dt < dt:
                            continue  # письмо раньше нашего since — пропускаем
                    except (ValueError, AttributeError) as e:
                        log.warning(f"Failed to parse msg date '{msg_date_str}': {e}")
                        # При ошибке парсинга — оставляем письмо (conservative)

                result.append({
                    "messageId": msg.get('Message-ID', '').strip('<> ').strip(),
                    "from": from_raw,
                    "email": email_addr,
                    "fio": fio,
                    "subject": decode_str(msg.get('Subject', '')),
                    "date": msg_date_str,
                    "body_text": get_body_text(msg)[:5000],
                    "attachment_names": get_attachment_names(msg),
                })
            except Exception as e:
                log.warning(f"Failed to parse msg {msg_id}: {e}")
                continue

        return result

    except imaplib.IMAP4.error as e:
        log.error(f"IMAP error: {e}")
        raise HTTPException(status_code=500, detail=f"IMAP error: {str(e)}")
    except Exception as e:
        log.error(f"Unexpected error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if M:
            try:
                M.logout()
            except Exception:
                pass
