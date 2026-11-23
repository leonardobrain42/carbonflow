#!/usr/bin/env python3
"""Inspect an authorized SENATRAN ZIP offline for #94 data feasibility.

This research report never writes application tables or produces admitted EVR,
weights, ratings or emission inputs. Production ingestion belongs to #92.
"""
import argparse
import csv
import hashlib
import io
import json
import re
import zipfile
from decimal import Decimal, InvalidOperation
from pathlib import Path


HEADERS = [
    "UF", "Município", "Marca Modelo",
    "Ano Fabricação Veículo CRV", "Qtd. Veículos",
]
MONTHS = {
    "janeiro": "01", "fevereiro": "02", "marco": "03", "março": "03",
    "abril": "04", "maio": "05", "junho": "06", "julho": "07",
    "agosto": "08", "setembro": "09", "outubro": "10",
    "novembro": "11", "dezembro": "12",
}


def quantity(raw, line):
    try:
        value = Decimal(raw.strip())
    except (InvalidOperation, AttributeError) as cause:
        raise ValueError(f"Linha {line}: quantidade ausente ou inválida") from cause
    if not value.is_finite() or value < 0 or value != value.to_integral_value():
        raise ValueError(f"Linha {line}: quantidade deve ser inteira e não negativa")
    return int(value)


def inspect(path, period, model_names):
    if not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", period):
        raise ValueError("Período de observação deve ser YYYY-MM")
    if not model_names or len(set(model_names)) != len(model_names):
        raise ValueError("Selecione nomes literais distintos; sem matching automático")
    samples = {name: {
        "registered_count": None, "source_rows": 0, "by_manufacture_year": {},
        "uf_labels": [], "examples": [],
    } for name in model_names}
    ufs = set()
    source_rows, all_registered = 0, 0
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    with zipfile.ZipFile(path) as archive:
        members = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len(members) != 1 or not members[0].filename.lower().endswith(".txt"):
            raise ValueError("Esperado exatamente um TXT agregado no ZIP")
        member = members[0]
        parts = Path(member.filename).stem.rsplit("_", 2)
        if len(parts) != 3 or period != parts[-1] + "-" + MONTHS.get(parts[-2].lower(), ""):
            raise ValueError("Período divergente do mês/ano do arquivo publicado")
        with archive.open(member) as stream:
            reader = csv.DictReader(io.TextIOWrapper(stream, encoding="utf-8-sig", newline=""), delimiter=";")
            if reader.fieldnames != HEADERS:
                raise ValueError(f"Schema não reconhecido: {reader.fieldnames!r}")
            for line, row in enumerate(reader, 2):
                if None in row or any(value is None for value in row.values()):
                    raise ValueError(f"Linha {line}: número de colunas divergente")
                count = quantity(row["Qtd. Veículos"], line)
                source_rows += 1
                all_registered += count
                ufs.add(row["UF"])
                sample = samples.get(row["Marca Modelo"])
                if sample is None:
                    continue
                sample["registered_count"] = (sample["registered_count"] or 0) + count
                sample["source_rows"] += 1
                year = row["Ano Fabricação Veículo CRV"]
                sample["by_manufacture_year"][year] = sample["by_manufacture_year"].get(year, 0) + count
                if row["UF"] not in sample["uf_labels"]:
                    sample["uf_labels"].append(row["UF"])
                if len(sample["examples"]) < 3:
                    sample["examples"].append({"line": line, "record": row})
    for sample in samples.values():
        sample["by_manufacture_year"] = dict(sorted(sample["by_manufacture_year"].items()))
        sample["uf_labels"].sort()
    return {
        "inspection_version": 1,
        "observation_period": period,
        "metric": "REGISTERED_VEHICLES",
        "source_sha256": digest.hexdigest(),
        "source_bytes": Path(path).stat().st_size,
        "archive_member": member.filename,
        "headers": HEADERS,
        "source_rows": source_rows,
        "all_registered_count": all_registered,
        "uf_labels": sorted(ufs),
        "selected_literal_models": samples,
        "evr_total": None,
        "weights": None,
        "usableForE8": False,
        "coverageState": "OFFICIAL_TOTAL_NOT_VERIFIED",
        "ratingState": "RATINGS_NOT_LINKED",
        "limitations": [
            "Ano Fabricação Veículo CRV não é o período de observação nem o ano do rating.",
            "Veículos registrados não comprovam circulação ativa, propulsão BEV, LDV ou compatibilidade DCFC.",
            "Nomes literais selecionados não demonstram cobertura nacional de todos os BEVs.",
            "Modelos sem linha continuam ausentes; nomes e anos desconhecidos são preservados.",
            "Alias, rating, capacidade, ciclo e comparável exigem prova e revisão nos módulos canônicos.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_file", type=Path)
    parser.add_argument("--period", required=True)
    parser.add_argument("--model", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.zip_file, args.period, args.model)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{result['source_rows']} linhas verificadas; relatório gravado em {args.output}")


if __name__ == "__main__":
    main()
