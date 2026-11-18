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
import statistics
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_charts import Measurement, parse_jmh  # noqa: E402

# Student t two-sided 95% critical values by degrees of freedom (n-1), n <= 30.
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365,
       8: 2.306, 9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145,
       15: 2.131, 16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086,
       21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060, 26: 2.056,
       27: 2.052, 28: 2.048, 29: 2.045}


def ci95(values):
    """Mean and half-width of the two-sided 95% CI (Student t)."""
    n = len(values)
    mean = statistics.fmean(values)
    if n < 2:
        return mean, 0.0
    sd = statistics.stdev(values)
    half = T95[min(n - 1, 29)] * sd / (n ** 0.5)
    return mean, half

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
IMPLS = ["v6", "v7", "v8", "Smile"]
IMPL_COLORS = {"v6": "#d62728", "v7": "#9467bd", "v8": "#7f7f7f", "Smile": "#8c564b"}


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


def _group_raw(raw_file: Path):
    """-> {(dataset, query, impl): dict(nodes=(mean,ci), nsnode=(mean,ci))}"""
    groups: dict = {}
    for r in csv.DictReader(raw_file.open(encoding="utf-8")):
        key = (r["dataset"], r["query"], r["impl"])
        groups.setdefault(key, []).append((int(r["total_ns"]), int(r["units"])))
    out = {}
    per_query = 500
    for key, reps in groups.items():
        nodes, nodes_ci = ci95([u / per_query for _, u in reps])
        per_node = [ns / u for ns, u in reps if u > 0]
        nsnode, nsnode_ci = ci95(per_node) if per_node else (0.0, 0.0)
        out[key] = {"nodes": (nodes, nodes_ci), "nsnode": (nsnode, nsnode_ci)}
    return out


def _write_summary(raw_file: Path, summary_file: Path):
    groups = _group_raw(raw_file)
    with summary_file.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["dataset", "query", "v6_nodes", "v6_nsnode", "v7_nodes", "v7_nsnode",
                    "v8_nodes", "v8_nsnode", "smile_nodes", "smile_nsnode"])
        seen = []
        for (dataset, query, _) in groups:
            if (dataset, query) not in seen:
                seen.append((dataset, query))
        for dataset, query in seen:
            row = [dataset, query]
            for impl in ("v6", "v7", "v8", "smile"):
                m = groups.get((dataset, query, impl), {})
                row += [f"{m.get('nodes', (0, 0))[0]:.1f}", f"{m.get('nsnode', (0, 0))[0]:.1f}"]
            w.writerow(row)


def chart_nodecost():
    out = CHARTS / "ecef"
    out.mkdir(parents=True, exist_ok=True)
    raw = GRID / "nodecost-raw.csv"
    if not raw.exists():
        return
    _write_summary(raw, GRID / "nodecost.csv")
    groups = _group_raw(raw)
    datasets = []
    for (dataset, _, _) in groups:
        if dataset not in datasets:
            datasets.append(dataset)
    for dataset in datasets:
        queries = []
        for (ds, q, _) in groups:
            if ds == dataset and q not in queries:
                queries.append(q)
        x = list(range(len(queries)))
        width = 0.2

        fig, axes = plt.subplots(1, 2, figsize=(13, 5))
        present = [i for i in IMPLS if (dataset, queries[0], i.lower()) in groups]
        slot = {impl: i for i, impl in enumerate(present)}
        for ax, metric, ylabel, title in (
            (axes[0], "nodes", "узлов / запрос", "Посещённые узлы на запрос, среднее ± 95% ДИ"),
            (axes[1], "nsnode", "нс / узел", "Цена одного узла, среднее ± 95% ДИ"),
        ):
            for impl in present:
                col = impl.lower()
                vals = [groups[(dataset, q, col)][metric][0] for q in queries]
                errs = [groups[(dataset, q, col)][metric][1] for q in queries]
                i = slot[impl]
                ax.bar([p + (i - (len(present) - 1) / 2.0) * width for p in x], vals,
                       width=width * 0.9,
                       yerr=errs, capsize=3, label=impl, color=IMPL_COLORS[impl])
            ax.set_xticks(x)
            ax.set_xticklabels(queries, fontsize=8, rotation=12)
            ax.set_ylabel(ylabel)
            ax.set_title(title)
            ax.legend(fontsize=9)
        fig.suptitle(f"Разложение стоимости запроса — {dataset}, n=100000, 15 прогонов (NodeCostRunner)", y=1.02)
        fig.savefig(out / f"nodecost-{dataset}.png")
        plt.close(fig)


def chart_realdata():
    out = CHARTS / "realdata"
    out.mkdir(parents=True, exist_ok=True)
    raw = GRID / "realdata-raw.csv"
    if not raw.exists():
        return

    per_query = 500
    groups: dict = {}
    for r in csv.DictReader(raw.open(encoding="utf-8")):
        key = (r["dataset"], r["query"], r["impl"])
        groups.setdefault(key, []).append(int(r["total_ns"]))

    # regenerate the summary csv as means (microseconds per query)
    with (GRID / "realdata.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["dataset", "query", "v6_us", "v7_us", "smile_us"])
        seen = []
        for (dataset, query, _) in groups:
            if (dataset, query) not in seen:
                seen.append((dataset, query))
        for dataset, query in seen:
            row = [dataset, query]
            for impl in ("v6", "v7", "smile"):
                vals = [ns / per_query / 1000.0 for ns in groups.get((dataset, query, impl), [0])]
                row.append(f"{statistics.fmean(vals):.2f}")
            w.writerow(row)

    prefixes = {"taxi": "NYC Taxi", "poi": "NYC OSM POIs"}
    for dataset, title in (
        ("taxi", "NYC Taxi, n=1 515 132 (кластеризован)"),
        ("poi", "NYC OSM POI, n=870 540 (proxy Twitter)"),
    ):
        prefix = prefixes[dataset]
        queries = []
        full_ds = None
        for (ds, q, _) in groups:
            if ds.startswith(prefix):
                full_ds = ds
                if q not in queries:
                    queries.append(q)
        x = list(range(len(queries)))
        present = [i for i in IMPLS if (full_ds, queries[0], i.lower()) in groups]
        width = 0.8 / max(len(present), 1)
        fig, ax = plt.subplots(figsize=(9, 5.5))
        all_means = []
        bar_data = []
        for i, impl in enumerate(present):
            col = impl.lower()
            vals = []
            errs = []
            for q in queries:
                reps_us = [ns / per_query / 1000.0 for ns in groups[(full_ds, q, col)]]
                mean, half = ci95(reps_us)
                vals.append(mean)
                errs.append(half)
            bar_data.append((impl, vals, errs))
            all_means.extend(vals)
        # log-scale bars cannot start at 0; start slightly below the smallest mean
        bottom = max(min(all_means) * 0.3, 1e-3)
        for i, (impl, vals, errs) in enumerate(bar_data):
            ax.bar([p + (i - (len(bar_data) - 1) / 2.0) * width for p in x],
                   [v - bottom for v in vals],
                   bottom=bottom, width=width * 0.9,
                   yerr=errs, capsize=3, label=impl, color=IMPL_COLORS[impl])
        ax.set_yscale("log")
        ax.set_xticks(x)
        ax.set_xticklabels(queries, fontsize=9)
        ax.set_ylabel("время на запрос, мкс (лог), среднее ± 95% ДИ")
        ax.set_title(title + " — RealDataRunner, 500 равномерных запросов, 15 прогонов")
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
