#!/usr/bin/env python3
"""Extract the electric (VE) rows of an Inmetro PBEV table PDF into a candidate list (#94/#95).

Offline research tool. It reads only what the published table prints (category, brand, model, version, propulsion),
never infers class, comparability or circulation, and never writes application tables. Every row is a candidate for
the canonical catalog and needs evidence and review there. The count printed in the PDF header is compared with the
extracted count; any mismatch is reported, never hidden.
"""
import argparse
from collections import Counter
import hashlib
import json
import re
import subprocess
from pathlib import Path

HEADER_COUNT = re.compile(r"Elétricos\s*\(VE\)\s+(\d+)\s+Modelos")
GAP = re.compile(r"\s{2,}")
PROPULSION = {"ELÉTRICO", "ELETRICO"}


def fold(text):
    return " ".join(text.split())


def pdf_text(path):
    return subprocess.run(["pdftotext", "-layout", str(path), "-"], check=True, capture_output=True,
                          text=True).stdout


def parse(text):
    """Returns (rows, header_count, skipped). A row is kept only if the fields before the propulsion column are
    unambiguous; anything else is listed in `skipped` with its line, never guessed."""
    header = HEADER_COUNT.search(text)
    rows, skipped = [], []
    for number, line in enumerate(text.splitlines(), 1):
        parts = [fold(part) for part in GAP.split(line.strip()) if part.strip()]
        index = next((i for i, part in enumerate(parts) if part.upper() in PROPULSION), None)
        # A data row prints the propulsion type and then the engine column, both "Elétrico". Header and footnote
        # fragments that only mention the word are not rows and are ignored without being counted as skipped.
        if index is None or index + 1 >= len(parts) or parts[index + 1].upper() not in PROPULSION:
            continue
        if index < 3 or index > 4 or parts[0].lower() == "categoria":
            skipped.append({"line": number, "reason": "campos antes da propulsão não são 3 ou 4", "text": fold(line)})
            continue
        head = parts[:index]
        category, brand, model = head[0], head[1], head[2]
        version = head[3] if index == 4 else None
        rows.append({"line": number, "category": category, "brand": brand, "model": model, "version": version,
                     "propulsion": "ELETRICO"})
    return rows, int(header.group(1)) if header else None, skipped


def extract(pdf_path, text=None):
    pdf = Path(pdf_path)
    rows, header, skipped = parse(text if text is not None else pdf_text(pdf))
    keys = [(r["brand"], r["model"], r["version"]) for r in rows]
    return {
        "extractor_version": 1,
        "source_sha256": hashlib.sha256(pdf.read_bytes()).hexdigest(),
        "header_electric_count": header,
        "extracted_rows": len(rows),
        "count_matches_header": header is not None and header == len(rows),
        "categories_observed": dict(sorted(Counter(r["category"] for r in rows).items())),
        "duplicate_keys": sorted({"|".join(map(str, k)) for k in keys if keys.count(k) > 1}),
        "skipped_lines": skipped,
        "rows": rows,
        "limitations": [
            "Um modelo no PBEV não prova circulação, classe LDV, comparabilidade nem compatibilidade DCFC.",
            "Marca, modelo e versão são os textos impressos; nenhum alias é decidido aqui.",
            "O texto extraído do PDF pode quebrar em colunas; linhas ambíguas são listadas, nunca adivinhadas.",
        ],
        "usableForE8": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = extract(args.pdf)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{result['extracted_rows']} linhas elétricas; cabeçalho: {result['header_electric_count']}; "
          f"conferem: {result['count_matches_header']}; ignoradas: {len(result['skipped_lines'])}")


if __name__ == "__main__":
    main()
