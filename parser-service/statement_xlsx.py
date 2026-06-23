"""ВТБ XLSX-адаптер: каждый лист = счёт. Тот же выходной формат и classify(),
что у PDF-парсера (statement_parser). Колонки XLSX:
0 Дата | 1 Номер | 2 Вид операции | 3 Контрагент | 4 ИНН | 5 БИК | 6 Счёт | 7 Дебет | 8 Кредит | 9 Назначение
Дебет>0 -> DEBIT (списание), Кредит>0 -> CREDIT (поступление).
"""
import sys, json, re
from decimal import Decimal
from datetime import datetime, date
import openpyxl
from statement_parser import classify, _owner_surname, _clean

DATE_RE = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
CENT = Decimal("0.01")


def _d(v):
    if v is None:
        return None
    if isinstance(v, datetime):
        return v.date().isoformat()
    if isinstance(v, date):
        return v.isoformat()
    s = str(v).strip()
    return datetime.strptime(s, "%d.%m.%Y").date().isoformat() if DATE_RE.match(s) else None


def _amt(v):
    if v is None or v == "":
        return Decimal("0")
    if isinstance(v, (int, float)):
        try:
            return Decimal(str(v)).quantize(CENT)
        except Exception:
            return Decimal("0")
    s = str(v).replace("\xa0", "").replace(" ", "").replace(",", ".")
    try:
        return Decimal(s or "0").quantize(CENT)
    except Exception:
        return Decimal("0")


def _find_after(rows, label, maxrow=6):
    for r in rows[:maxrow]:
        for i, c in enumerate(r):
            if c is not None and label in str(c):
                for nxt in r[i + 1:]:
                    if nxt not in (None, ""):
                        return nxt
    return None


def parse_xlsx(path):
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    statements = []
    for ws in wb.worksheets:
        rows = [list(r) for r in ws.iter_rows(values_only=True)]
        acc = (ws.title or "").strip()
        owner_name = _find_after(rows, "Владелец")
        owner_name = str(owner_name).strip() if owner_name else None
        oi = _find_after(rows, "ИНН")
        owner_inn = None
        if oi is not None:
            m_oi = re.search(r"(\d{12})", str(oi))
            owner_inn = m_oi.group(1) if m_oi else None
        if not owner_inn:
            for rr in rows[:8]:
                for cc in rr:
                    if cc is not None:
                        mm = re.search(r"\b(\d{12})\b", str(cc))
                        if mm:
                            owner_inn = mm.group(1); break
                if owner_inn:
                    break
        sd, ed = _d(_find_after(rows, "Начальная дата")), _d(_find_after(rows, "Конечная дата"))
        period = (sd, ed) if (sd and ed) else None
        sn = _owner_surname(owner_name or "")
        ops = []
        for r in rows:
            if not r or len(r) < 10:
                continue
            d = _d(r[0])
            if not d:                      # не строка операции (титул/шапка/мета)
                continue
            deb, cre = _amt(r[7]), _amt(r[8])
            inn = str(r[4]).strip() if r[4] is not None else ""
            if inn and set(inn) <= {"0"}:
                inn = ""
            name = _clean(str(r[3]) if r[3] is not None else "")
            vo = str(r[2]).strip() if r[2] is not None else ""
            ops.append({
                "operation_date": d,
                "amount": str(deb if deb > 0 else cre),
                "direction": "DEBIT" if deb > 0 else "CREDIT",
                "purpose": _clean(str(r[9]) if r[9] is not None else ""),
                "counterparty_inn_raw": inn,
                "counterparty_name_raw": name,
                "_vo": vo,
                "_doc_no": str(r[1]).strip() if r[1] is not None else "",
                "_category": classify(inn, name, vo, owner_inn, sn),
            })
        statements.append({
            "account": acc, "bank_name": "ВТБ (ПАО)",
            "period_start": period[0] if period else None,
            "period_end": period[1] if period else None,
            "owner_name": owner_name, "owner_inn": owner_inn, "operations": ops,
            "_open": str(_amt(_find_after(rows, "Входящий остаток"))),
            "_close": str(_amt(_find_after(rows, "Исходящий остаток"))),
        })
    return {"bank": "vtb", "statements": statements}


if __name__ == "__main__":
    print(json.dumps(parse_xlsx(sys.argv[1]), ensure_ascii=False))
