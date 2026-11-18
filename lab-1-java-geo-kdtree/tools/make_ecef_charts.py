#!/usr/bin/env python3
"""Charts for the ECEF experiment and the real-data benchmark.

Reads the committed measurement artifacts:
- JMH grids:  report/bench/grid/{perf6-trigcache,ecef,public-smile}-{knn,radius}.txt
- Node decomposition: report/bench/grid/nodecost.csv (NodeCostRunner)
- Real datasets:      report/bench/grid/realdata.csv  (RealDataRunner: NYC taxi, OSM POI)

Outputs PNG charts into report/charts/ecef/ and report/charts/realdata/.
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_charts import Measurement, parse_jmh  # noqa: E402

plt.rcParams.update({
    "font.size": 11,
    "axes.grid": True,
    "grid.alpha": 0.3,
    "axes.axisbelow": True,
    "figure.dpi": 110,
    "savefig.dpi": 200,
    "savefig.bbox": "tight",
})

ROOT = Path(__file__).resolve().parents[1]
GRID = ROOT / "report/bench/grid"
CHARTS = ROOT / "report/charts"

SERIES = [
    ("v6 (геодезия)", "perf6-trigcache", {"color": "#d62728", "linestyle": "-"}),
    ("v7 (ECEF-ядро)", "ecef", {"color": "#9467bd", "linestyle": "-"}),
    ("Smile KDTree", "public-smile", {"color": "#8c564b", "linestyle": "--"}),
]
METHOD_OF = {
    "perf6-trigcache": {"radius": "queryKdTreeRadius", "knn": "queryKdTreeKnn"},
    "ecef": {"radius": "queryEcefKdTreeRadius", "knn": "queryEcefKdTreeKnn"},
    "public-smile": {"radius": "querySmileRadius", "knn": "querySmileKnn"},
}
FAMILIES = [
    ("radius", "100", "Radius-поиск, r = 100 м"),
    ("radius", "5000", "Radius-поиск, r = 5000 м"),
    ("knn", "1", "kNN, k = 1"),
    ("knn", "20", "kNN, k = 20"),
]
IMPLS = ["v6", "v7", "Smile"]
IMPL_COLORS = {"v6": "#d62728", "v7": "#9467bd", "Smile": "#8c564b"}


def load_grid():
    data: dict[str, dict] = {}
    for _, prefix, _ in SERIES:
        merged: dict = {}
        for fam in ("knn", "radius"):
            f = GRID / f"{prefix}-{fam}.txt"
            if f.exists():
                merged.update(parse_jmh(f))
        data[prefix] = merged
    return data


def chart_throughput(data):
    out = CHARTS / "ecef"
    out.mkdir(parents=True, exist_ok=True)
    for fam, param, title in FAMILIES:
        fig, ax = plt.subplots(figsize=(8, 5))
        for label, prefix, style in SERIES:
            merged = data[prefix]
            method = METHOD_OF[prefix][fam]
            xs = sorted(n for (m, n, p) in merged if m == method and p == param)
            if not xs:
                continue
            ys = [merged[(method, n, param)].mean for n in xs]
            lo = [merged[(method, n, param)].err_lo for n in xs]
            hi = [merged[(method, n, param)].err_hi for n in xs]
            ax.plot(xs, ys, marker="o", markersize=3.5, label=label, linewidth=1.8, **style)
            ax.fill_between(xs, [y - l for y, l in zip(ys, lo)],
                            [y + h for y, h in zip(ys, hi)],
                            color=style["color"], alpha=0.15, linewidth=0)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("число точек в индексе, N")
        ax.set_ylabel("throughput, ops/s")
        ax.set_title(f"{title} — v6 против v7 (ECEF) против Smile, с ДИ")
        ax.legend(fontsize=9)
        fig.savefig(out / f"throughput-{fam}-{param}.png")
        plt.close(fig)


def chart_nodecost():
    out = CHARTS / "ecef"
    out.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader((GRID / "nodecost.csv").open(encoding="utf-8")))
    for dataset in ("uniform", "clustered"):
        subset = [r for r in rows if r["dataset"] == dataset]
        labels = [r["query"] for r in subset]
        x = list(range(len(labels)))
        width = 0.27

        fig, axes = plt.subplots(1, 2, figsize=(13, 5))
        for ax, metric, ylabel, title in (
            (axes[0], "nodes", "узлов / запрос", "Посещённые узлы на запрос"),
            (axes[1], "nsnode", "нс / узел", "Цена одного узла"),
        ):
            for i, impl in enumerate(IMPLS):
                col = impl.lower()
                vals = [float(r[f"{col}_{metric}"]) for r in subset]
                ax.bar([p + (i - 1) * width for p in x], vals, width=width * 0.9,
                       label=impl, color=IMPL_COLORS[impl])
            ax.set_xticks(x)
            ax.set_xticklabels(labels, fontsize=8, rotation=12)
            ax.set_ylabel(ylabel)
            ax.set_title(title)
            ax.legend(fontsize=9)
        fig.suptitle(f"Разложение стоимости запроса — {dataset}, n=100000 (NodeCostRunner)", y=1.02)
        fig.savefig(out / f"nodecost-{dataset}.png")
        plt.close(fig)


def chart_realdata():
    out = CHARTS / "realdata"
    out.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader((GRID / "realdata.csv").open(encoding="utf-8")))
    for dataset, title in (
        ("taxi", "NYC Taxi, n=1 515 132 (кластеризован)"),
        ("poi", "NYC OSM POI, n=870 540 (proxy Twitter)"),
    ):
        subset = [r for r in rows if r["dataset"] == dataset]
        labels = [r["query"] for r in subset]
        x = list(range(len(labels)))
        width = 0.27
        fig, ax = plt.subplots(figsize=(9, 5.5))
        for i, impl in enumerate(IMPLS):
            vals = [float(r[f"{impl.lower() if impl == 'Smile' else impl}_us"]) for r in subset]
            ax.bar([p + (i - 1) * width for p in x], vals, width=width * 0.9,
                   label=impl, color=IMPL_COLORS[impl])
        ax.set_yscale("log")
        ax.set_xticks(x)
        ax.set_xticklabels(labels, fontsize=9)
        ax.set_ylabel("время на запрос, мкс (лог)")
        ax.set_title(title + " — RealDataRunner, 500 равномерных запросов")
        ax.legend(fontsize=9)
        fig.savefig(out / f"{dataset}.png")
        plt.close(fig)


def main() -> int:
    data = load_grid()
    chart_throughput(data)
    chart_nodecost()
    chart_realdata()
    print(f"charts written to {CHARTS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
