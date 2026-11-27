import gzip
import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import build_fleet_universe as bfu
from match_senatran_pbev import match as original_match

HEADER = "UF;Município;Marca Modelo;Ano Fabricação Veículo CRV;Qtd. Veículos\n"
NAME = "I_Frota_por_UF_Municipio_Marca_e_Modelo_Ano_Setembro_2026.TXT"


def row(brand, model, version, line=1, category="Médio"):
    return {"brand": brand, "model": model, "version": version, "category": category, "line": line}


PBEV = [
    row("BYD", "DOLPHIN", "GS 180EV", 1),
    row("BYD", "DOLPHIN MINI", "GS 5 EV", 2, "Sub Compacto"),
    row("BYD", "DOLPHIN MINI", "GL 5 EV", 3, "Sub Compacto"),
    row("Renault", "E-KWID", "INTENS", 4),
    row("PORSCHE", "CAYENNE", "E", 6, "Extra Grande"),
    row("PORSCHE", "CAYENNE", "E", 7, "Utilitário Esportivo Grande 4x4"),
    row("AUDI", "A6", "e-tron", 8),
]
LINES = [
    "ACRE;RIO BRANCO;I/BYD DOLPHIN MINI GS 5 EV;2024; 10.0",          # EXACT
    "SAO PAULO;SAO PAULO;I/BYD DOLPHIN MINI GS 5 EV;2025; 5.0",
    "SAO PAULO;CAMPINAS;I/BYD DOLPHIN MINI XX;2025; 7.0",            # MODEL_ONLY
    "SAO PAULO;SAO PAULO;I/PORSCHE CAYENNE E;2023; 3.0",             # EXACT: two PBEV lines share one key; both are listed, none chosen
    "BAHIA;SALVADOR;FIAT/UNO MILLE;1999; 100.0",                      # NONE
    "BAHIA;SALVADOR;VW/GOL 1.0;Sem Informação; 40.0",                 # NONE, unknown manufacture year
    "Não Identificado;Não se Aplica;FIAT/UNO MILLE;2001; 4.0",        # unknown UF label preserved
    "RIO DE JANEIRO;NITEROI;A B;2020; 2.0",                            # "A B" and "A_B" collapse to the same record_key
    "RIO DE JANEIRO;NITEROI;A_B;2020; 3.0",
]


def make_zip(directory, lines=LINES, header=HEADER, name=NAME, extra=None):
    path = Path(directory) / "source.zip"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(name, "﻿" + header + "".join(line + "\n" for line in lines))
        for member, data in (extra or {}).items():
            archive.writestr(member, data)
    return path


def manifest_for(directory, zip_path, registered=None, name="source.zip"):
    path = Path(directory) / "manifest.json"
    path.write_text(json.dumps({
        "downloads": [{"file": name, "url": "https://example.invalid/x.zip", "bytes": zip_path.stat().st_size,
                       "sha256": bfu.sha256_file(zip_path), "captured_at": "2026-11-23T00:00:00+00:00"}],
        "fuel_cross_check": {"total_all_fuels": registered}}), encoding="utf-8")
    return path


def pbev_file(directory, rows=PBEV, matches=True):
    path = Path(directory) / "pbev.json"
    path.write_text(json.dumps({"source_sha256": "a" * 64, "count_matches_header": matches, "rows": rows}), encoding="utf-8")
    return path


class UniverseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def run_all(self, lines=LINES, registered=None, expected_rows=None, **kwargs):
        zip_path = make_zip(self.dir, lines, **kwargs)
        manifest = manifest_for(self.dir, zip_path, registered)
        return bfu.run(zip_path, "2026-09", manifest, "source.zip", pbev_file(self.dir), self.dir / "out", expected_rows)

    def universe(self):
        with gzip.open(self.dir / "out" / "universe.jsonl.gz", "rt", encoding="utf-8") as stream:
            return [json.loads(line) for line in stream]

    def test_every_literal_is_in_the_universe_without_a_model_list_and_totals_reconcile(self):
        summary = self.run_all(registered=sum(float(x.split(";")[-1]) for x in LINES), expected_rows=len(LINES))
        records = self.universe()
        self.assertEqual(7, summary["totals"]["distinct_literal_models"])
        self.assertEqual(len(records), summary["totals"]["distinct_literal_models"])
        self.assertEqual(summary["totals"]["registered_vehicles"], sum(r["registered_count"] for r in records))
        self.assertEqual({"rows_match": True, "registered_match": True}, summary["reconciliation"])
        self.assertEqual(len(LINES), summary["totals"]["rows"])

    def test_reconciliation_flags_divergence_and_marks_unchecked_as_none(self):
        summary = self.run_all(registered=1, expected_rows=len(LINES) + 1)
        self.assertEqual({"rows_match": False, "registered_match": False}, summary["reconciliation"])
        unchecked = self.run_all()
        self.assertEqual({"rows_match": None, "registered_match": None}, unchecked["reconciliation"])

    def test_unknowns_are_preserved_and_nothing_is_promoted(self):
        summary = self.run_all()
        records = {r["literal_name"]: r for r in self.universe()}
        for record in records.values():
            self.assertEqual("UNKNOWN_IN_SOURCE", record["propulsion"])
            self.assertFalse(record["usableForE8"])
            self.assertFalse(record["approved"])
        self.assertEqual("UNKNOWN_NO_PBEV_MATCH", records["FIAT/UNO MILLE"]["classification"])
        self.assertEqual(104.0, records["FIAT/UNO MILLE"]["registered_count"])  # an unmatched model keeps its count
        self.assertEqual({"Não Identificado": 4}, summary["locations"]["unknown_labels_preserved"])
        self.assertEqual({"Sem Informação": 40}, summary["manufacture_year"]["non_numeric_labels_registered"])
        self.assertFalse(summary["usableForE8"])
        self.assertIsNone(summary["evr_total"])
        self.assertIsNone(summary["weights"])

    def test_candidate_levels_follow_the_pbev_evidence_only(self):
        self.run_all()
        records = {r["literal_name"]: r for r in self.universe()}
        self.assertEqual("PBEV_EXACT_CANDIDATE", records["I/BYD DOLPHIN MINI GS 5 EV"]["classification"])
        self.assertEqual(15, records["I/BYD DOLPHIN MINI GS 5 EV"]["registered_count"])
        self.assertEqual("PBEV_MODEL_ONLY_CANDIDATE", records["I/BYD DOLPHIN MINI XX"]["classification"])
        self.assertEqual("EXACT", records["I/PORSCHE CAYENNE E"]["match"]["level"])
        self.assertEqual(2, len(records["I/PORSCHE CAYENNE E"]["match"]["candidates"]))  # both PBEV lines, none chosen

    def test_indexed_matcher_is_equivalent_to_the_original(self):
        names = ["I/BYD DOLPHIN MINI GS 5 EV", "I/BYD DOLPHIN MINI XX", "BYD/DOLPHIN GS180EV", "I/PORSCHE CAYENNE E",
                 "I/RENAULT E-KWID INTENS", "I/AUDI A6 E-TRON QUATTRO", "FIAT/UNO", "", "VW/GOL", "I/BYD DOLPHIN",
                 "BYD DOLPHIN MINI", "AUDI A6"]
        index = bfu.PbevIndex(PBEV)
        for name in names:
            expected = original_match(name, PBEV)
            got = index.match(name)
            for field in ("status", "level", "reason"):
                self.assertEqual(expected[field], got[field], f"{name!r} {field}")
            key = lambda c: (c["pbev_line"], c["brand"], c["model"], c["version"])
            self.assertEqual(sorted(expected["candidates"], key=key), sorted(got["candidates"], key=key), name)

    def test_colliding_record_keys_are_refused_from_the_csv_and_listed_never_merged(self):
        summary = self.run_all()
        self.assertEqual(1, summary["record_keys"]["collisions"])
        self.assertEqual(2, summary["record_keys"]["literals_refused_from_csv"])
        records = self.universe()
        self.assertEqual({"A B", "A_B"}, {r["literal_name"] for r in records if r["record_key"] == "A_B"})
        candidates = json.loads((self.dir / "out" / "candidates.json").read_text(encoding="utf-8"))
        self.assertEqual(1, len(candidates["refused_key_collisions"]))
        self.assertEqual({"A B", "A_B"}, set(next(iter(candidates["refused_key_collisions"].values()))))

    def test_artifacts_respect_parser_and_api_limits_and_are_partial_by_declaration(self):
        many = [f"SAO PAULO;SAO PAULO;I/BYD DOLPHIN MINI VERSAO{n};2025; 1.0" for n in range(2105)]
        summary = self.run_all(many)
        artifacts = summary["artifacts"]
        self.assertEqual(2105, artifacts["candidates"]["sendable"])
        self.assertTrue(all(b["items"] <= bfu.MAX_IMPORT_ITEMS for b in artifacts["alias_candidate_batches"]))
        self.assertEqual(5, len(artifacts["alias_candidate_batches"]))
        self.assertTrue(all(c["rows"] <= bfu.MAX_ROWS for c in artifacts["stock_csv_chunks"]))
        self.assertEqual(2, len(artifacts["stock_csv_chunks"]))
        self.assertIn("CANDIDATES_ONLY", artifacts["stock_csv_scope"])
        first = (self.dir / "out" / "stock-candidates-001.csv").read_text(encoding="utf-8").splitlines()
        self.assertEqual("record_key,period_year,metric,value,unit,geography", first[0])
        self.assertEqual("2026", first[1].split(",")[1])
        body = json.loads((self.dir / "out" / "alias-candidates-import-001.json").read_text(encoding="utf-8"))
        self.assertEqual("2026-09", body["observationPeriod"])
        self.assertEqual("a" * 64, body["pbevSourceSha256"])
        for batch in artifacts["alias_candidate_batches"]:  # recorded hashes match the files
            self.assertEqual(batch["sha256"], bfu.sha256_file(self.dir / "out" / batch["file"]))

    def test_output_is_deterministic(self):
        self.run_all()
        first = bfu.sha256_file(self.dir / "out" / "universe.jsonl.gz")
        self.run_all()
        self.assertEqual(first, bfu.sha256_file(self.dir / "out" / "universe.jsonl.gz"))

    def test_malformed_sources_fail_loudly_and_write_nothing(self):
        cases = {
            "quantidade inválida": dict(lines=["ACRE;X;A/B;2020;abc"]),
            "quantidade fracionária": dict(lines=["ACRE;X;A/B;2020; 1.5"]),
            "quantidade negativa": dict(lines=["ACRE;X;A/B;2020;-1"]),
            "colunas a mais": dict(lines=["ACRE;X;A/B;2020;1;extra"]),
            "modelo vazio": dict(lines=["ACRE;X; ;2020;1"]),
            "cabeçalho": dict(lines=["ACRE;X;A/B;2020;1"], header="UF;Cidade;Modelo;Ano;Qtd\n"),
            "período do arquivo": dict(lines=["ACRE;X;A/B;2020;1"], name="I_Frota_por_UF_Municipio_Marca_e_Modelo_Ano_Outubro_2026.TXT"),
        }
        for label, kwargs in cases.items():
            with self.subTest(label), self.assertRaises(ValueError):  # SourceError is a ValueError
                self.run_all(**kwargs)
            self.assertFalse((self.dir / "out").exists(), label)

    def test_source_integrity_is_checked_against_the_manifest(self):
        zip_path = make_zip(self.dir)
        manifest = manifest_for(self.dir, zip_path)
        data = json.loads(manifest.read_text(encoding="utf-8"))
        data["downloads"][0]["sha256"] = "0" * 64
        manifest.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(bfu.SourceError, "divergem do manifesto"):
            bfu.run(zip_path, "2026-09", manifest, "source.zip", None, self.dir / "out")
        with self.assertRaisesRegex(bfu.SourceError, "não consta"):
            bfu.run(zip_path, "2026-09", manifest, "outro.zip", None, self.dir / "out")

    def test_zip_guards_reject_multiple_members_oversize_and_unsafe_names(self):
        zip_path = make_zip(self.dir, extra={"outro.txt": "x"})
        with self.assertRaisesRegex(bfu.SourceError, "exatamente um TXT"):
            bfu.stream_universe(zip_path, "2026-09")
        zip_path = make_zip(self.dir)
        with self.assertRaisesRegex(bfu.SourceError, "acima do limite"):
            bfu.stream_universe(zip_path, "2026-09", max_expanded=10)
        zip_path = make_zip(self.dir, name="../" + NAME)
        with self.assertRaisesRegex(bfu.SourceError, "inseguro"):
            bfu.stream_universe(zip_path, "2026-09")
        with self.assertRaisesRegex(bfu.SourceError, "YYYY-MM"):
            bfu.stream_universe(make_zip(self.dir), "2026-9")

    def test_compression_bomb_and_understated_headers_are_refused(self):
        bomb = self.dir / "bomb.zip"
        with zipfile.ZipFile(bomb, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(NAME, "\0" * (8 * 1024 * 1024))  # ~1000x: far above the declared ratio limit
        with self.assertRaisesRegex(bfu.SourceError, "ZIP bomb"):
            bfu.stream_universe(bomb, "2026-09")
        # The byte counter does not trust the header: reading past the limit fails even if the metadata said otherwise.
        counter = bfu.Counting(io.BytesIO(b"x" * 100), limit=10)
        with self.assertRaisesRegex(bfu.SourceError, "acima do limite"):
            counter.read(50)

    def test_a_pbev_extraction_that_does_not_match_its_header_is_refused(self):
        zip_path = make_zip(self.dir)
        manifest = manifest_for(self.dir, zip_path)
        with self.assertRaisesRegex(bfu.SourceError, "cabeçalho"):
            bfu.run(zip_path, "2026-09", manifest, "source.zip", pbev_file(self.dir, matches=False), self.dir / "out")

    def test_without_pbev_every_model_stays_unknown(self):
        zip_path = make_zip(self.dir)
        manifest = manifest_for(self.dir, zip_path)
        summary = bfu.run(zip_path, "2026-09", manifest, "source.zip", None, self.dir / "out")
        self.assertEqual({"UNKNOWN_PBEV_NOT_PROVIDED"}, set(summary["classification"]["literal_models"]))
        self.assertEqual(0, summary["artifacts"]["candidates"]["count"])


if __name__ == "__main__":
    unittest.main()
