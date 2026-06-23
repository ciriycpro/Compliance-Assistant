"""
Универсальный экстрактор операций банковских выписок (этап 1) — DEC-008 / Сценарий 0.
Авто-детект банка -> адаптер -> единый формат MoneyOperation (сырые поля).
Поддержка: ВТБ (ПАО), АО «Альфа-Банк». Детерминированный разбор (pdfplumber), без LLM.
Расширение: добавить detect_* + parse_* под новый банк, общий формат не меняется.
"""
import re, json, sys
from decimal import Decimal
from datetime import datetime
import pdfplumber

FNS_INN = "7727406020"  # Казначейство/ФНС

# ---------- общие утилиты ----------
def _amount(s):
    if not s: return Decimal("0")
    s = s.replace("\xa0", "").replace(" ", "").replace(",", ".")
    return Decimal(s or "0")

def _clean(s):
    return re.sub(r"\s+", " ", s.replace("\n", " ")).strip() if s else ""

def _date(s):
    return datetime.strptime(s.strip(), "%d.%m.%Y").date().isoformat()

def _owner_surname(name):
    toks = re.findall(r"[А-ЯЁA-Z][а-яёa-zА-ЯЁA-Z]{3,}", (name or "").upper())
    toks = [t for t in toks if t not in ("ИНДИВИДУАЛЬНЫЙ","ПРЕДПРИНИМАТЕЛЬ")]
    return toks[0] if toks else None

def classify(inn, name, vo, owner_inn, owner_surname):
    nu = (name or "").upper()
    if (owner_inn and inn == owner_inn) or (owner_surname and owner_surname in nu):
        return "OWN_TRANSFER"
    if inn == FNS_INN or "КАЗНАЧЕЙСТВО" in nu or "ФНС" in nu:
        return "TAX"
    if vo == "02" or "БАНК" in nu:
        return "BANK_FEE"
    if vo == "17":
        return "CARD_ACQUIRING"
    return "COUNTERPARTY"

def _new_stmt(acc, bank, period):
    return {"account": acc, "bank_name": bank,
            "period_start": period[0] if period else None,
            "period_end": period[1] if period else None,
            "owner_name": None, "owner_inn": None, "operations": []}

# ---------- адаптер ВТБ ----------
def parse_vtb(pdf):
    stmts, cur, period, owner = {}, None, None, {"name":None,"inn":None,"sn":None}
    for page in pdf.pages:
        t = page.extract_text() or ""
        m = re.search(r"Счет\s+(\d{20})", t)
        if m:
            cur = m.group(1)
            pr = re.search(r"с\s+(\d{2}\.\d{2}\.\d{4})\s+по\s+(\d{2}\.\d{2}\.\d{4})", t)
            period = (_date(pr.group(1)), _date(pr.group(2))) if pr else None
            ow = re.search(r"Владелец счета:\s*([^\n]+)", t)
            oi = re.search(r"ИНН[^\d]{0,6}(\d{12})", t)
            owner = {"name": ow.group(1).strip() if ow else None,
                     "inn": oi.group(1) if oi else None,
                     "sn": _owner_surname(ow.group(1) if ow else "")}
            s = stmts.setdefault(cur, _new_stmt(cur, "ВТБ (ПАО)", period))
            s["owner_name"] = owner["name"]
            s["owner_inn"] = owner["inn"]
        for tb in page.extract_tables() or []:
            for r in tb:
                if not r or len(r) < 10 or not re.match(r"\d{2}\.\d{2}\.\d{4}$", (r[0] or "").strip()):
                    continue
                deb, cre = _amount(r[7]), _amount(r[8])
                inn, name, vo = (r[3] or "").strip(), _clean(r[6]), (r[2] or "").strip()
                stmts[cur]["operations"].append({
                    "operation_date": _date(r[0]), "amount": str(deb if deb>0 else cre),
                    "direction": "DEBIT" if deb>0 else "CREDIT", "purpose": _clean(r[9]),
                    "counterparty_inn_raw": inn, "counterparty_name_raw": name,
                    "_vo": vo, "_doc_no": (r[1] or "").strip(),
                    "_category": classify(inn, name, vo, owner["inn"], owner["sn"])})
    return list(stmts.values())

# ---------- адаптер Альфа ----------
def parse_alfa(pdf):
    first = pdf.pages[0].extract_text() or ""
    acc_m = re.search(r"Сч[её]т:\s*([\d\s]{20,})", first)
    acc = re.sub(r"\s", "", acc_m.group(1))[:20] if acc_m else "unknown"
    pr = re.search(r"Период:\D*(\d{2}\.\d{2}\.\d{4})\s+по\s+(\d{2}\.\d{2}\.\d{4})", first)
    period = (_date(pr.group(1)), _date(pr.group(2))) if pr else None
    ow = re.search(r"Владелец сч[её]та:\s*([^\n]+)", first)
    owner_name = ow.group(1).strip() if ow else None
    # ИНН владельца — 12-значный в шапке (ИП), не банковский 10-значный
    oi = re.search(r"ИНН:?\s*(\d{12})", first)
    owner = {"inn": oi.group(1) if oi else None, "sn": _owner_surname(owner_name)}
    s = _new_stmt(acc, "АО «Альфа-Банк»", period); s["owner_name"] = owner_name; s["owner_inn"] = owner["inn"]
    for page in pdf.pages:
        for tb in page.extract_tables() or []:
            for r in tb:
                if not r or len(r) < 7 or not re.match(r"\d{2}\.\d{2}\.\d{4}$", (r[0] or "").strip()):
                    continue
                deb, cre = _amount(r[2]), _amount(r[3])
                cp = r[4] or ""
                name = _clean(re.split(r"ИНН\s*:|[Рр]\s*[/\\]\s*[Сс]|Сч[её]т", cp, maxsplit=1)[0]) if cp else ""
                im = re.search(r"ИНН:\s*(\d{10,12})", cp)
                inn = im.group(1) if im else ""
                if inn and set(inn) <= {"0"}:
                    inn = ""
                s["operations"].append({
                    "operation_date": _date(r[0]), "amount": str(deb if deb>0 else cre),
                    "direction": "DEBIT" if deb>0 else "CREDIT", "purpose": _clean(r[6]),
                    "counterparty_inn_raw": inn, "counterparty_name_raw": name,
                    "_vo": "", "_doc_no": (r[1] or "").strip(),
                    "_category": classify(inn, name, "", owner["inn"], owner["sn"])})
    return [s]

# ---------- роутер ----------
def detect_bank(pdf):
    t = (pdf.pages[0].extract_text() or "").upper()
    if "АЛЬФА" in t: return "alfa"
    if "ВТБ" in t:   return "vtb"
    return "unknown"

def parse(path):
    with pdfplumber.open(path) as pdf:
        bank = detect_bank(pdf)
        if bank == "vtb":  return {"bank": bank, "statements": parse_vtb(pdf)}
        if bank == "alfa": return {"bank": bank, "statements": parse_alfa(pdf)}
        raise ValueError("Неизвестный формат банка")

if __name__ == "__main__":
    print(json.dumps(parse(sys.argv[1]), ensure_ascii=False))
