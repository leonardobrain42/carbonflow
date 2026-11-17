"""Compara o relatório do Quarkus com o oráculo Decimal do kit 0.2.14."""

import argparse
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import sys


def check(kit: Path, report_path: Path, snapshot_path: Path) -> None:
    specification = kit / "03_especificacao"
    sys.path.insert(0, str(specification / "e2"))
    from conferir_calculos import Entrada, Fator, Modelo, calcular, desconto

    source_bytes = (specification / "e6/exemplos.json").read_bytes()
    report = json.loads(report_path.read_bytes())
    snapshot_bytes = snapshot_path.read_bytes()
    snapshot = json.loads(snapshot_bytes)
    assert report["environment"] == "SIMULACAO"
    assert report["provenance"]["kitVersion"] == "0.2.14"
    assert report["provenance"]["sourceSha256"] == sha256(source_bytes).hexdigest()
    assert report["snapshotSha256"] == sha256(snapshot_bytes).hexdigest()
    assert snapshot["provenance"] == report["provenance"]
    source = json.loads(source_bytes)["calculation"]
    assert snapshot["fixture"]["calculation"]["factor"] == source["factor"] == report["factor"]
    assert snapshot["fixture"]["calculation"]["replacementCandidate"] == source["replacementCandidate"]
    models = tuple(
        Modelo(model["count"], Decimal(model["evKwhPer100Mi"]), Decimal(model["mpg"]))
        for model in source["models"]
    )
    factor = Fator(source["factor"]["id"], Decimal(source["factor"]["value"]))
    dy = desconto(excecao_comprovada=True)
    measures = ("be", "pe", "le", "er")
    assert Decimal(report["results"]["dy"]) == dy

    for period, actual in zip(source["periods"], report["results"]["periods"], strict=True):
        assert actual["id"] == period["id"]
        expected_totals = dict.fromkeys(measures, Decimal(0))
        for cut, observed in zip(period["cuts"], actual["cuts"], strict=True):
            assert (observed["start"], observed["endExclusive"]) == (cut["start"], cut["endExclusive"])
            assert (Decimal(observed["edKwh"]), Decimal(observed["ecKwh"])) == (
                Decimal(cut["edKwh"]), Decimal(cut["ecKwh"])
            )
            expected = calcular(
                Entrada(period["year"], models, Decimal(cut["edKwh"]), Decimal(cut["ecKwh"]),
                        Decimal(source["fuelFactorTco2PerGal"]), Decimal(source["ir"]), factor),
                dy,
            )
            assert Decimal(report["results"]["afecKwhPer100Mi"]) == expected.afec
            assert Decimal(report["results"]["mpg"]) == expected.mpg
            for measure in measures:
                value = getattr(expected, measure)
                assert Decimal(observed["emissions"][measure]) == value, (period["id"], cut["start"], measure)
                expected_totals[measure] += value
            assert observed["emissions"]["erDisplay"] == cut["erTco2e"]
            print(f'{period["id"]} [{cut["start"]}, {cut["endExclusive"]}) '
                  f'BE={expected.be}, PE={expected.pe}, LE={expected.le}, ER={expected.er}')
        for measure, total in expected_totals.items():
            assert Decimal(actual["emissions"][measure]) == total, (period["id"], measure)
        assert actual["emissions"]["erDisplay"] == period["erTco2e"]
        print(f'{period["id"]} total ER={actual["emissions"]["erDisplay"]} tCO2e')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kit", type=Path, help="Raiz do kit 0.2.14 extraído")
    parser.add_argument("relatorio", type=Path)
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    check(args.kit, args.relatorio, args.snapshot)
