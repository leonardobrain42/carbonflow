#!/usr/bin/env python3
"""Importer of the HOURLY table of the MCTI/SIRENE dispatch emission-factor workbook (D19 slice 4, CF-DATA-003).

Offline. Reads an .xlsx with the standard library only; writes nothing to the application and never sends the file.
It preserves the original (SHA-256, sheet, cell locator, day/hour/month labels and the value text exactly as stored)
and derives a normalized view only from parameters that PEOPLE must supply. Nothing is presumed:
  --timezone           IANA zone of the labels (no default; the workbook does not say it).
  --hour-convention    HOUR_ENDING_1_24  (label h covers [h-1:00, h:00))
                       HOUR_START_LABEL_1_24 (label h covers [h:00, h+1:00); 24 starts the next day at 00:00)
  --unit               unit the sheet title declares; must equal what is parsed from the title, else the import stops.
  --target-unit        optional dimensional normalization: tCO2/MWh (identity), kgCO2/MWh or gCO2/kWh (exact, x1000).
The gas is CO2 as declared; CO2e equivalence is NOT assumed. Eligibility under VT0011/TOOL07 and the right to process
the data are NOT evaluated here: the output always says usableForE8=false and rights UNCONFIRMED.
Local times that do not exist or are ambiguous in the zone are flagged and get no UTC instant (never guessed).
"""
import argparse
import calendar
import hashlib
import json
import re
import sys
import unicodedata
import zipfile
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from xml.etree import ElementTree as ET
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      "pr": "http://schemas.openxmlformats.org/package/2006/relationships"}
CONVENTIONS = ("HOUR_ENDING_1_24", "HOUR_START_LABEL_1_24")
FACTORS = {("tCO2/MWh", "tCO2/MWh"): Decimal(1), ("tCO2/MWh", "kgCO2/MWh"): Decimal(1000),
           ("tCO2/MWh", "gCO2/kWh"): Decimal(1000)}
MONTHS = ["janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
          "novembro", "dezembro"]
LIMITS = ["Importação estrutural, não admissão: nada aqui é fator admitido nem prova elegibilidade VT0011/TOOL07.",
          "O fuso e a convenção da hora vêm de parâmetros de pessoas; a planilha não os declara.",
          "O gás é CO2 como declarado na planilha; equivalência com CO2e não é presumida.",
          "Direito de processar o arquivo (licença do site, uso restrito a MDL) não confirmado.",
          "O arquivo original não é versionado nem enviado por esta ferramenta."]


class ImportErrorStop(Exception):
    pass


def fold(text):
    return re.sub(r"[^A-Z0-9]", "", unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().upper())


def col_number(ref):
    n = 0
    for ch in re.match(r"[A-Z]+", ref).group(0):
        n = n * 26 + ord(ch) - 64
    return n


def sheets_of(zf):
    book = ET.fromstring(zf.read("xl/workbook.xml"))
    rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(zf.read("xl/_rels/workbook.xml.rels")).findall("pr:Relationship", NS)}
    out = []
    for sheet in book.find("m:sheets", NS):
        target = rels[sheet.get("{%s}id" % NS["r"])]
        out.append((sheet.get("name"), "xl/" + target.lstrip("/").removeprefix("xl/")))
    return out


def shared_strings(zf):
    if "xl/sharedStrings.xml" not in zf.namelist():
        return []
    return ["".join(t.text or "" for t in si.iter("{%s}t" % NS["m"]))
            for si in ET.fromstring(zf.read("xl/sharedStrings.xml")).findall("m:si", NS)]


def read_rows(zf, path, strings):
    """{row: {column_number: (ref, kind, text)}} where kind is 'n' (number text as stored) or 's' (string)."""
    rows = {}
    for row in ET.fromstring(zf.read(path)).iter("{%s}row" % NS["m"]):
        for c in row.findall("m:c", NS):
            ref, kind = c.get("r"), c.get("t", "n")
            if kind == "inlineStr":
                value = "".join(t.text or "" for t in c.iter("{%s}t" % NS["m"]))
                cell = (ref, "s", value)
            else:
                v = c.find("m:v", NS)
                if v is None or v.text is None:
                    continue
                cell = (ref, "s", strings[int(v.text)]) if kind == "s" else (ref, "s", v.text) if kind in ("str", "e") else (ref, "n", v.text)
            rows.setdefault(int(re.search(r"\d+", ref).group(0)), {})[col_number(ref)] = cell
    return rows


def locate(zf, sheet_name, marker):
    strings, hits = shared_strings(zf), []
    for name, path in sheets_of(zf):
        if sheet_name and name != sheet_name:
            continue
        rows = read_rows(zf, path, strings)
        for r, cells in rows.items():
            for col, (ref, kind, text) in cells.items():
                if kind == "s" and fold(marker) in fold(text):
                    hits.append((name, rows, r, ref, text))
    if len(hits) != 1:
        raise ImportErrorStop(f"o marcador '{marker}' deve aparecer exatamente uma vez; achei {len(hits)}")
    return hits[0]


def resolve(naive, zone):
    """Returns (local_iso, utc_iso, issue). A time that does not exist or is ambiguous gets no UTC instant."""
    a, b = naive.replace(tzinfo=zone, fold=0), naive.replace(tzinfo=zone, fold=1)
    utc = a.astimezone(ZoneInfo("UTC")).replace(tzinfo=None)
    if utc.replace(tzinfo=ZoneInfo("UTC")).astimezone(zone).replace(tzinfo=None) != naive:
        return naive.isoformat(), None, "LOCAL_TIME_NONEXISTENT"
    if a.utcoffset() != b.utcoffset():
        return naive.isoformat(), None, "LOCAL_TIME_AMBIGUOUS"
    return a.isoformat(), utc.isoformat() + "Z", None


def to_decimal(text):
    try:
        value = Decimal(text)
    except InvalidOperation:
        return None
    return value if value.is_finite() else None


def importer(path, *, timezone, hour_convention, unit, target_unit=None, sheet=None, marker="HORARIO", year=None):
    if hour_convention not in CONVENTIONS:
        raise ImportErrorStop(f"convenção de hora inválida; use uma de {CONVENTIONS}")
    try:
        zone = ZoneInfo(timezone)
    except (ZoneInfoNotFoundError, ValueError, TypeError):
        raise ImportErrorStop("fuso horário desconhecido; informe um nome IANA")
    target = target_unit or unit
    if (unit, target) not in FACTORS:
        raise ImportErrorStop(f"não há conversão exata definida de {unit} para {target}")
    data = Path(path).read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    with zipfile.ZipFile(path) as zf:
        sheet_name, rows, title_row, title_ref, title = locate(zf, sheet, marker)
    parsed_unit = re.search(r"\(([^)]*)\)", title)
    parsed_unit = re.sub(r"\s+", "", parsed_unit.group(1)) if parsed_unit else None
    if parsed_unit != unit:
        raise ImportErrorStop(f"a unidade declarada ({unit}) difere da do título da planilha ({parsed_unit})")
    header_row = next((r for r in sorted(rows) if r > title_row and {fold(c[2]) for c in rows[r].values() if c[1] == "s"} >=
                       {"DIA", "HORA", "JANEIRO", "DEZEMBRO"}), None)
    if header_row is None:
        raise ImportErrorStop("cabeçalho Dia/Hora/meses não encontrado abaixo do título")
    month_cols = {}
    for col, (_, kind, text) in rows[header_row].items():
        if kind == "s" and fold(text) in [fold(m) for m in MONTHS]:
            month_cols[col] = [fold(m) for m in MONTHS].index(fold(text)) + 1
    day_col = next(c for c, v in rows[header_row].items() if fold(v[2]) == "DIA")
    hour_col = next(c for c, v in rows[header_row].items() if fold(v[2]) == "HORA")
    found_year = next((int(cell[2]) for r in range(title_row + 1, header_row) for cell in rows.get(r, {}).values()
                       if cell[1] == "n" and re.fullmatch(r"\d{4}", cell[2])), None)
    if found_year is None:
        raise ImportErrorStop("ano não encontrado entre o título e o cabeçalho")
    if year is not None and year != found_year:
        raise ImportErrorStop(f"o ano informado ({year}) difere do da planilha ({found_year})")
    factor = FACTORS[(unit, target)]
    records, issues, filled = [], [], {m: 0 for m in range(1, 13)}
    day, expected_hour, last_row = None, 1, header_row
    for r in range(header_row + 1, max(rows) + 1):
        cells = rows.get(r, {})
        if not cells or (day_col not in cells and hour_col not in cells and not any(c in month_cols for c in cells)):
            break
        if day_col in cells:
            new_day = int(Decimal(cells[day_col][2]))
            if day is None and new_day != 1 or day is not None and (new_day != day + 1 or expected_hour != 25):
                raise ImportErrorStop(f"estrutura de dias/horas quebrada na linha {r}: dia {new_day} após dia {day}, hora esperada {expected_hour}")
            day, expected_hour = new_day, 1
        if day is None or hour_col not in cells or int(Decimal(cells[hour_col][2])) != expected_hour:
            raise ImportErrorStop(f"hora fora de sequência na linha {r}: esperada {expected_hour}")
        hour = expected_hour
        expected_hour += 1
        last_row = r
        for col, month in month_cols.items():
            if col not in cells:
                continue
            ref, kind, text = cells[col]
            valid_day = day <= calendar.monthrange(found_year, month)[1]
            value = to_decimal(text) if kind == "n" else None
            if not valid_day:
                issues.append({"cell": ref, "code": "VALUE_ON_NONEXISTENT_DAY", "month": month, "day": day})
                continue
            if value is None:
                issues.append({"cell": ref, "code": "NON_NUMERIC_VALUE", "month": month, "day": day, "text": text})
                continue
            if value < 0:
                issues.append({"cell": ref, "code": "NEGATIVE_VALUE", "month": month, "day": day, "hour": hour})
            start = datetime(found_year, month, day) + timedelta(hours=hour - 1 if hour_convention == "HOUR_ENDING_1_24" else hour)
            s_local, s_utc, s_issue = resolve(start, zone)
            e_local, e_utc, e_issue = resolve(start + timedelta(hours=1), zone)
            records.append({"cell": ref, "month": month, "day": day, "hourLabel": hour, "valueOriginal": text,
                            "value": format(value * factor, "f"), "unit": target,
                            "intervalStartLocal": s_local, "intervalEndLocal": e_local,
                            "intervalStartUtc": s_utc, "intervalEndUtc": e_utc,
                            "timeIssue": s_issue or e_issue})
            filled[month] += 1
    if day is not None and expected_hour != 25:
        raise ImportErrorStop(f"último dia ({day}) termina na hora {expected_hour - 1}, não em 24")
    months = {}
    for m in range(1, 13):
        expected = calendar.monthrange(found_year, m)[1] * 24
        state = "NOT_PUBLISHED" if filled[m] == 0 else "COMPLETE" if filled[m] == expected else "PARTIAL"
        months[str(m)] = {"expectedCells": expected, "importedCells": filled[m], "state": state}
    return {"importerVersion": 1,
            "original": {"fileSha256": sha, "fileBytes": len(data), "sheet": sheet_name, "titleCell": title_ref,
                         "titleText": title, "headerRow": header_row, "lastDataRow": last_row, "year": found_year,
                         "unitAsDeclaredInTitle": parsed_unit, "gas": "CO2"},
            "parameters": {"timezone": timezone, "hourConvention": hour_convention, "unit": unit, "targetUnit": target,
                           "tableMarker": marker, "setByPeople": True},
            "quality": {"months": months, "recordCount": len(records), "issues": issues,
                        "timeIssueCount": sum(1 for x in records if x["timeIssue"])},
            "records": records, "co2eEquivalence": "NOT_ASSUMED", "rights": "UNCONFIRMED",
            "eligibilityVT0011Tool07": "NOT_EVALUATED", "usableForE8": False, "limitations": LIMITS}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--xlsx", type=Path, required=True)
    p.add_argument("--timezone", required=True)
    p.add_argument("--hour-convention", required=True, choices=CONVENTIONS)
    p.add_argument("--unit", required=True)
    p.add_argument("--target-unit")
    p.add_argument("--sheet")
    p.add_argument("--table-marker", default="HORARIO")
    p.add_argument("--year", type=int)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    try:
        out = importer(a.xlsx, timezone=a.timezone, hour_convention=a.hour_convention, unit=a.unit,
                       target_unit=a.target_unit, sheet=a.sheet, marker=a.table_marker, year=a.year)
    except ImportErrorStop as stop:
        print(f"importação interrompida: {stop}", file=sys.stderr)
        return 2
    a.output.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    states = {k: v["state"] for k, v in out["quality"]["months"].items()}
    print(f"{out['quality']['recordCount']} registros, {len(out['quality']['issues'])} anomalias, meses {states}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
