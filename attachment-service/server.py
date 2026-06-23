"""
Attachment Service v1 — по DEC-011.

Скачивание вложений из IMAP по messageId+filename.
Хранилище в /var/lib/mail-stack/attachments/<messageId>/<filename>.
Кэш через файловую систему (idempotent).
TTL 7 дней через atime + cleanup-cron.
Hard limit 25 МБ, soft warning > 10 МБ.

Переиспользует MIME-парсинг из mail-service v1 (consistent style).
"""

import os
import re
import hashlib
import logging
import imaplib
import email
from datetime import datetime, timezone
from email.header import decode_header
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


# === Конфиг через env (docker-friendly по DEC-007) ===
IMAP_HOST = os.getenv('IMAP_HOST', 'imap.mail.ru')
IMAP_PORT = int(os.getenv('IMAP_PORT', '993'))
IMAP_USER = os.getenv('IMAP_USER')
IMAP_PASS = os.getenv('IMAP_PASS')
DEFAULT_MAILBOX = os.getenv('IMAP_MAILBOX', 'INBOX')

# Multi-mailbox support (DEC-014 v1.1 parity с mail-service).
# Если MAILBOXES_JSON задан — ищем письмо последовательно по всем ящикам.
# Иначе — fallback к single-mailbox через IMAP_HOST/USER/PASS.
import json as _json
MAILBOXES_JSON = os.getenv('MAILBOXES_JSON', '')
MAILBOXES = []
if MAILBOXES_JSON:
    try:
        MAILBOXES = _json.loads(MAILBOXES_JSON)
    except _json.JSONDecodeError:
        MAILBOXES = []

STORAGE_DIR = Path(os.getenv('ATTACHMENT_STORAGE_DIR', '/var/lib/mail-stack/attachments'))
HARD_LIMIT_MB = int(os.getenv('ATTACHMENT_HARD_LIMIT_MB', '25'))
SOFT_WARNING_MB = int(os.getenv('ATTACHMENT_SOFT_WARNING_MB', '10'))

# Гарантируем существование хранилища
STORAGE_DIR.mkdir(parents=True, exist_ok=True)


# === Логирование в stdout (docker-friendly) ===
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
)
log = logging.getLogger("attachment-service")


# === Модели request/response (контракт DEC-011) ===
class DownloadRequest(BaseModel):
    messageId: str
    filename: str
    label: str | None = None   # DEC-022 parity
    group: str | None = None


class DownloadResponse(BaseModel):
    path: str
    size_bytes: int
    size_mb: float
    sha256: str
    mime: str
    from_cache: bool
    downloaded_at: str


# === Утилиты (переиспользованы из mail-service для консистентности) ===
def decode_str(s):
    """Декодирует MIME-encoded строку (=?utf-8?b?...?= и т.п.)."""
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


def safe_filename(filename: str) -> str:
    """Очистка имени файла от path traversal и спецсимволов."""
    # Убираем все ../ и / для защиты
    name = filename.replace('..', '').replace('/', '_').replace('\\', '_')
    # Убираем control characters
    name = re.sub(r'[\x00-\x1f\x7f]', '', name)
    return name.strip() or 'unnamed_attachment'


def safe_messageid_path(message_id: str) -> str:
    """Безопасное представление messageId как имени каталога."""
    # messageId может содержать @ и . — это ок для FS
    # Но убираем path-разделители и спецсимволы
    safe = message_id.replace('/', '_').replace('\\', '_').strip('<> ')
    safe = re.sub(r'[\x00-\x1f\x7f]', '', safe)
    # Длина каталога не более 200 символов (FS limit)
    return safe[:200] if len(safe) > 200 else safe


def compute_sha256(path: Path) -> str:
    """SHA256 файла на лету (DEC-011: v1 — на лету, v2 — кэш в .meta.json)."""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()


def detect_mime(filename: str) -> str:
    """Простая определялка mime по расширению (v1). v2 — magic bytes."""
    ext = Path(filename).suffix.lower()
    return {
        '.pdf': 'application/pdf',
        '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        '.doc': 'application/msword',
        '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        '.xls': 'application/vnd.ms-excel',
        '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        '.jpg': 'image/jpeg',
        '.jpeg': 'image/jpeg',
        '.png': 'image/png',
        '.gif': 'image/gif',
        '.html': 'text/html',
        '.htm': 'text/html',
        '.csv': 'text/csv',
        '.txt': 'text/plain',
        '.xml': 'application/xml',
        '.json': 'application/json',
        '.zip': 'application/zip',
        '.eml': 'message/rfc822',
    }.get(ext, 'application/octet-stream')


def build_response(path: Path, from_cache: bool) -> DownloadResponse:
    """Сборка response из существующего файла."""
    size_bytes = path.stat().st_size
    size_mb = round(size_bytes / 1024 / 1024, 2)
    return DownloadResponse(
        path=str(path),
        size_bytes=size_bytes,
        size_mb=size_mb,
        sha256=compute_sha256(path),
        mime=detect_mime(path.name),
        from_cache=from_cache,
        downloaded_at=datetime.now(timezone.utc).isoformat(),
    )


# === IMAP логика ===
def _build_mailbox_configs() -> list[dict]:
    """MAILBOXES_JSON если задан, иначе fallback к single-mailbox."""
    if MAILBOXES:
        return MAILBOXES
    return [{
        "label": "default",
        "host": IMAP_HOST,
        "port": IMAP_PORT,
        "user": IMAP_USER,
        "pass": IMAP_PASS,
        "mailbox": DEFAULT_MAILBOX,
    }]


def _imap_find_message(message_id: str, label: str | None = None, group: str | None = None):
    """
    Перебирает MAILBOXES, ищет письмо по Message-ID.
    Возвращает (M, uid, label) при первом попадании или None.
    M остаётся открытым с активным SELECT — caller обязан logout.
    Short-circuit: на первом найденном ящике выходим, остальные не открываем.
    """
    clean_id = message_id.strip('<> ')
    search_query = f'HEADER Message-ID "{clean_id}"'

    # Filter MAILBOXES by label/group/default (mirror mail-service /mail/since)
    if MAILBOXES:
        if label:
            boxes = [mb for mb in MAILBOXES if mb.get("label") == label]
        elif group:
            boxes = [mb for mb in MAILBOXES if mb.get("group") == group]
        else:
            boxes = [mb for mb in MAILBOXES if mb.get("default", True)]
        if not boxes:
            log.info(f"No mailbox matched (label={label}, group={group})")
            return None
    else:
        boxes = _build_mailbox_configs()

    for mb in boxes:
        label = mb.get('label', mb.get('user', '?'))
        M = None
        try:
            M = imaplib.IMAP4_SSL(mb['host'], int(mb.get('port', 993)))
            M.login(mb['user'], mb['pass'])
            M.select(mb.get('mailbox', 'INBOX'))
            typ, data = M.search(None, search_query)
            if typ == 'OK' and data and data[0]:
                ids = data[0].decode().split()
                if ids:
                    uid = ids[0]
                    log.info(f"[{label}] Found message uid={uid} for messageId={message_id}")
                    return M, uid, label
        except imaplib.IMAP4.error as e:
            log.warning(f"[{label}] IMAP error: {e}")
        except Exception as e:
            log.warning(f"[{label}] Unexpected error: {e}")
        if M:
            try:
                M.logout()
            except Exception:
                pass
    return None


def find_attachment_bytes(message_id: str, target_filename: str, label: str | None = None, group: str | None = None) -> tuple[bytes, str]:
    """
    Подключается к IMAP (multi-mailbox), находит письмо по messageId,
    выбирает вложение по filename.
    """
    result = _imap_find_message(message_id, label=label, group=group)
    if result is None:
        log.warning(f"Message not found in any mailbox: {message_id}")
        raise HTTPException(
            status_code=404,
            detail={
                "error": "message_not_found",
                "messageId": message_id,
                "filename": target_filename,
            },
        )

    M, msg_id, mailbox_label = result

    try:
        # Скачиваем письмо целиком (с .PEEK чтобы не помечать как прочитанное)
        typ, msg_data = M.fetch(msg_id, '(BODY.PEEK[])')
        if typ != 'OK' or not msg_data or not msg_data[0]:
            raise HTTPException(
                status_code=500,
                detail=f"IMAP fetch failed for uid {msg_id}",
            )

        raw = msg_data[0][1]
        msg = email.message_from_bytes(raw)

        # Перебираем части письма, ищем вложение по filename
        if not msg.is_multipart():
            raise HTTPException(
                status_code=404,
                detail={
                    "error": "attachment_not_found",
                    "messageId": message_id,
                    "filename": target_filename,
                    "reason": "message has no parts (not multipart)",
                },
            )

        for part in msg.walk():
            if part.get_content_disposition() != 'attachment':
                continue
            
            part_filename = decode_str(part.get_filename() or '')
            log.info(f"  found attachment in message: '{part_filename}'")
            
            # Сравниваем имя — может прийти декодированное или с экранированием
            if part_filename == target_filename or safe_filename(part_filename) == safe_filename(target_filename):
                payload = part.get_payload(decode=True)
                if not payload:
                    raise HTTPException(
                        status_code=500,
                        detail=f"Attachment found but payload is empty: {target_filename}",
                    )
                
                # Проверка hard limit
                size_mb = len(payload) / 1024 / 1024
                if size_mb > HARD_LIMIT_MB:
                    raise HTTPException(
                        status_code=413,
                        detail={
                            "error": "attachment_too_large",
                            "size_mb": round(size_mb, 2),
                            "limit_mb": HARD_LIMIT_MB,
                        },
                    )
                
                # Soft warning
                if size_mb > SOFT_WARNING_MB:
                    log.warning(f"large_attachment: {target_filename} = {size_mb:.2f} MB")
                
                return payload, part_filename

        # Письмо найдено, но вложения с таким именем нет
        all_filenames = [
            decode_str(p.get_filename() or '')
            for p in msg.walk()
            if p.get_content_disposition() == 'attachment'
        ]
        raise HTTPException(
            status_code=404,
            detail={
                "error": "attachment_not_found",
                "messageId": message_id,
                "filename": target_filename,
                "reason": "message exists but no part matches filename",
                "available_filenames": all_filenames,
            },
        )

    except imaplib.IMAP4.error as e:
        log.error(f"IMAP error: {e}")
        raise HTTPException(status_code=500, detail=f"IMAP error: {str(e)}")
    finally:
        if M:
            try:
                M.logout()
            except Exception:
                pass


# === FastAPI app ===
app = FastAPI(title="attachment-service-v1")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "attachment-service-v1",
        "storage_dir": str(STORAGE_DIR),
        "storage_exists": STORAGE_DIR.exists(),
        "hard_limit_mb": HARD_LIMIT_MB,
        "soft_warning_mb": SOFT_WARNING_MB,
    }


@app.post("/download", response_model=DownloadResponse)
def download(req: DownloadRequest):
    """
    Скачать вложение по messageId+filename.
    
    Алгоритм (по DEC-011):
    1. Сборка пути назначения: STORAGE_DIR/<messageId>/<filename>
    2. Если файл существует → возврат с from_cache=true (без IMAP)
    3. Иначе → FETCH с IMAP, сохранение, возврат с from_cache=false
    """
    # Безопасные имена для FS
    safe_msgid = safe_messageid_path(req.messageId)
    safe_fname = safe_filename(req.filename)
    
    target_dir = STORAGE_DIR / safe_msgid
    target_path = target_dir / safe_fname
    
    # Проверка кэша
    if target_path.exists() and target_path.is_file():
        log.info(f"Cache hit: {target_path}")
        return build_response(target_path, from_cache=True)
    
    # IMAP-fetch
    log.info(f"Cache miss, fetching from IMAP: {req.messageId}/{req.filename}")
    payload, real_filename = find_attachment_bytes(req.messageId, req.filename, label=req.label, group=req.group)
    
    # Сохраняем
    target_dir.mkdir(parents=True, exist_ok=True)
    with open(target_path, 'wb') as f:
        f.write(payload)
    
    log.info(f"Saved attachment: {target_path} ({len(payload)} bytes)")
    
    return build_response(target_path, from_cache=False)
