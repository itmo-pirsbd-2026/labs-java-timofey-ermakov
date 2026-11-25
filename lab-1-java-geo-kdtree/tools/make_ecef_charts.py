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
import numpy as np

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
    ("LatLong", "perf6-trigcache", {"color": "#d62728", "linestyle": "-"}),
    ("ECEF", "ecef", {"color": "#9467bd", "linestyle": "-"}),
    ("Google S2", "public-s2", {"color": "#2ca02c", "linestyle": "--"}),
]
MAIN_SERIES = SERIES
STATIC_SERIES = [
    ("ECEF", "ecef", {"color": "#9467bd", "linestyle": "-"}),
    ("Smile KDTree (статика)", "public-smile", {"color": "#8c564b", "linestyle": "--"}),
]
METHOD_OF = {
    "perf6-trigcache": {"radius": "queryKdTreeRadius", "knn": "queryKdTreeKnn"},
    "ecef": {"radius": "queryEcefKdTreeRadius", "knn": "queryEcefKdTreeKnn"},
    "public-smile": {"radius": "querySmileRadius", "knn": "querySmileKnn"},
    "public-s2": {"radius": "queryS2Radius", "knn": "queryS2Knn"},
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
    merged_series = list(SERIES)
    for _, prefix, _ in STATIC_SERIES:
        if all(prefix != p for _, p, _ in merged_series):
            merged_series.append((None, prefix, None))
    for _, prefix, _ in merged_series:
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
        ax.set_title(f"{title} — LatLong / ECEF / S2 (± ДИ)")
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


def chart_static_comparison():
    """Illustrations for the static-vs-dynamic document (Smile included)."""
    out = CHARTS / "static"
    out.mkdir(parents=True, exist_ok=True)
    data = load_grid()
    for fam, param, title in FAMILIES:
        fig, ax = plt.subplots(figsize=(8, 5))
        for label, prefix, style in STATIC_SERIES:
            merged = data[prefix]
            method = METHOD_OF[prefix][fam]
            xs = sorted(n for (m, n, p) in merged if m == method and p == param)
            if not xs:
                continue
            ys = [merged[(method, n, param)].mean for n in xs]
            lo = [merged[(method, n, param)].err_lo for n in xs]
            hi = [merged[(method, n, param)].err_hi for n in xs]
            ax.plot(xs, ys, marker="o", markersize=3.5, label=label, linewidth=1.8, **style)
            ax.fill_between(xs, [y - l for y, l in zip(ys, lo)], [y + h for y, h in zip(ys, hi)],
                            color=style["color"], alpha=0.15, linewidth=0)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("число точек в индексе, N")
        ax.set_ylabel("throughput, ops/s")
        ax.set_title(f"{title} — динамическое ядро против статического Smile")
        ax.legend(fontsize=9)
        fig.savefig(out / f"throughput-{fam}-{param}.png")
        plt.close(fig)


def chart_nodecost():
    out = CHARTS / "static"
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
        short = dataset.split(" ")[0]
        fig.savefig(out / f"nodecost-{short}.png")
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
    all_impls = []
    for (_, _, impl) in groups:
        if impl not in all_impls:
            all_impls.append(impl)
    with (GRID / "realdata.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["dataset", "query"] + [f"{i}_us" for i in all_impls])
        seen = []
        for (dataset, query, _) in groups:
            if (dataset, query) not in seen:
                seen.append((dataset, query))
        for dataset, query in seen:
            row = [dataset, query]
            for impl in all_impls:
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
        if full_ds is None:
            continue
        x = list(range(len(queries)))
        present = [i for i in IMPLS if i != "Smile" and (full_ds, queries[0], i.lower()) in groups]
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


REALDATA_FILES = ("realdata-raw.csv", "realdata-raw-taxi-8m.csv", "realdata-raw-taxi-s2.csv")
REALDATA_QUERIES = ("radius r=100", "radius r=1000", "radius r=5000", "kNN k=1", "kNN k=20")
REALDATA_DATASETS = [
    ("poi", "POI\n0.87 млн"),
    ("taxi", "Taxi\n1.5 млн"),
    ("taxi8m", "Taxi\n8 млн"),
]
REALDATA_IMPLS = [
    ("v6", "LatLong", "#d62728"),
    ("v7", "ECEF", "#9467bd"),
    ("s2", "Google S2", "#2ca02c"),
]


def _realdata_reps():
    """-> {(dataset_key, query, impl): [мкс/запрос по прогонам]}

    Dataset names contain commas and the 8m file is not quoted, so row fields
    are taken from the end; the query is matched against the known names.
    realdata-raw-taxi.csv duplicates the taxi part of realdata-raw.csv and is
    skipped on purpose.
    """
    reps: dict = {}
    for name in REALDATA_FILES:
        path = GRID / name
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as fh:
            rd = csv.reader(fh)
            next(rd, None)
            for parts in rd:
                if len(parts) < 6:
                    continue
                head = ",".join(parts[:-4])
                query = next((q for q in REALDATA_QUERIES if head.endswith(q)), None)
                if query is None:
                    continue
                impl = parts[-4].strip('"')
                us = int(parts[-2]) / 500 / 1000.0
                if "OSM POIs" in head:
                    ds = "poi"
                elif name.endswith("taxi-8m.csv"):
                    ds = "taxi8m"
                else:
                    ds = "taxi"
                reps.setdefault((ds, query, impl), []).append(us)
    return reps


def chart_realdata_2x2():
    """Real-data analog of the tenm 2x2 panel: scenarios x datasets, our two
    implementations plus the single-run S2 reference (Smile excluded, see the
    static-vs-dynamic charts)."""
    reps = _realdata_reps()
    if not reps:
        return
    out = CHARTS / "realdata"
    out.mkdir(parents=True, exist_ok=True)

    fig, axes = plt.subplots(2, 3, figsize=(15, 9))
    for ax, query in zip(axes.flat, REALDATA_QUERIES):
        x = list(range(len(REALDATA_DATASETS)))
        present = [(col, label, color) for col, label, color in REALDATA_IMPLS
                   if any((ds, query, col) in reps for ds, _ in REALDATA_DATASETS)]
        width = 0.8 / max(len(present), 1)
        all_means = []
        bars = []
        for col, label, color in present:
            vals, errs = [], []
            for ds, _ in REALDATA_DATASETS:
                values = reps.get((ds, query, col))
                if values:
                    mean, half = ci95(values)
                else:
                    mean, half = 0.0, 0.0
                vals.append(mean)
                errs.append(half)
            bars.append((label, color, vals, errs))
            all_means.extend(v for v in vals if v > 0)
        bottom = max(min(all_means) * 0.3, 1e-3)
        for i, (label, color, vals, errs) in enumerate(bars):
            ax.bar([p + (i - (len(bars) - 1) / 2.0) * width for p in x],
                   [v - bottom if v > 0 else 0 for v in vals],
                   bottom=bottom, width=width * 0.9,
                   yerr=[[min(e, v - bottom) if v > 0 else 0 for v, e in zip(vals, errs)],
                         [e if v > 0 else 0 for v, e in zip(vals, errs)]],
                   capsize=3, label=label, color=color)
        ax.set_yscale("log")
        ax.set_xticks(x)
        ax.set_xticklabels([label for _, label in REALDATA_DATASETS], fontsize=9)
        ax.set_title(query)
        ax.set_ylabel("мкс на запрос (лог)")
    legend_ax = axes.flat[len(REALDATA_QUERIES)]
    legend_ax.axis("off")
    handles, labels = axes.flat[0].get_legend_handles_labels()
    legend_ax.legend(handles, labels, loc="upper left", fontsize=11)
    legend_ax.text(0, 0.35,
                   "RealDataRunner, 500 запросов на прогон,\n"
                   "среднее ± 95% ДИ; прогонов: 15 (Taxi 8 млн: 5).\n"
                   "S2 на POI — одна запись (лучший из 15).\n"
                   "S2 на Taxi — без r=5000: прогон остановлен\n"
                   "как несопоставимый (клеточный скан кластера).\n"
                   "Smile исключён: сравнение со статикой —\n"
                   "в charts/static/.",
                   fontsize=10, va="top")
    fig.suptitle("Реальные данные: NYC OSM POI (0.87 млн) и NYC Taxi (1.5 и 8 млн посадок)")
    fig.savefig(out / "realdata-2x2.png")
    plt.close(fig)
    print("realdata 2x2 panel written")


SEL_PANELS = [
    ("knn k=10", "kNN, k = 10"),
    ("knn k=100", "kNN, k = 100"),
    ("sel 0.0001%", "Radius, доля выдачи 0.0001%"),
    ("sel 0.01%", "Radius, доля выдачи 0.01%"),
    ("sel 0.1%", "Radius, доля выдачи 0.1%"),
    ("sel 1%", "Radius, доля выдачи 1%"),
]
SEL_IMPLS = [
    ("v6", "LatLong", {"color": "#d62728", "linestyle": "-"}),
    ("v7", "ECEF", {"color": "#9467bd", "linestyle": "-"}),
    ("s2", "Google S2", {"color": "#2ca02c", "linestyle": "--"}),
    ("lucene", "Lucene LatLonPoint", {"color": "#17becf", "linestyle": ":"}),
]
SEL_DATASETS = [
    ("uniform-bbox", "Равномерный bbox NYC (без кластеров)"),
    ("taxi", "NYC Taxi (кластерный)"),
    ("poi", "NYC OSM POI (распределённый)"),
    ("geonames", "GeoNames cities500 (мировые города)"),
    ("geonames-all", "GeoNames allCountries (мировые города)"),
]


def chart_selectivity():
    """TUM-methodology panels: throughput vs N for kNN k=10/100 and radius queries
    whose per-query radius is adapted to a fixed output selectivity."""
    raw_files = sorted(GRID.glob("selectivity-raw-*.csv"))
    if not raw_files:
        return
    out = CHARTS / "selectivity"
    out.mkdir(parents=True, exist_ok=True)

    # {(dataset, panel, impl): {n: [ops/s per rep]}}
    cells: dict = {}
    for raw in raw_files:
        with raw.open(encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                key = (r["dataset"], r["panel"], r["impl"])
                ops = int(r["nq"]) / (int(r["total_ns"]) / 1e9)
                cells.setdefault(key, {}).setdefault(int(r["n"]), []).append(ops)

    for ds, ds_title in SEL_DATASETS:
        if not any(key[0] == ds for key in cells):
            continue
        fig, axes = plt.subplots(2, 3, figsize=(16, 9))
        for ax, (panel, title) in zip(axes.flat, SEL_PANELS):
            for impl, label, style in SEL_IMPLS:
                series = cells.get((ds, panel, impl))
                if not series:
                    continue
                xs = sorted(series)
                means, los, his = [], [], []
                for n in xs:
                    mean, half = ci95(series[n])
                    means.append(mean)
                    los.append(half)
                    his.append(half)
                ax.plot(xs, means, marker="o", markersize=3.5, label=label,
                        linewidth=1.8, **style)
                ax.fill_between(xs, [m - l for m, l in zip(means, los)],
                                [m + h for m, h in zip(means, his)],
                                color=style["color"], alpha=0.15, linewidth=0)
            ax.set_xscale("log")
            ax.set_yscale("log")
            ax.set_title(title, fontsize=11)
            ax.set_xlabel("число точек в индексе, N")
            ax.set_ylabel("throughput, ops/s")
        handles, labels = axes.flat[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc="lower center", ncol=4, fontsize=10,
                   bbox_to_anchor=(0.5, 0.0))
        fig.suptitle(f"{ds_title}\nметодология Pandey et al. (2021), "
                     f"doi:10.1007/s41019-020-00147-9: радиус подобран под долю выдачи, "
                     f"запросы равномерные (geonames: по данным), {REPS_TXT} прогонов, ±95% ДИ",
                     fontsize=12)
        fig.tight_layout(rect=(0, 0.05, 1, 0.96))
        fig.savefig(out / f"{ds}-2x3.png")
        plt.close(fig)
    print("selectivity panels written")


REPS_TXT = "5"


def chart_dataset_distribution():
    """2x2 scatter of the four selectivity-benchmark datasets (sampled ~30k points)."""
    data_dir = ROOT / ".bench-collection/data"
    if not data_dir.exists():
        return
    out = CHARTS / "selectivity"
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(42)
    n_show = 30_000

    panels = []
    lat = 40.45 + 0.5 * rng.random(n_show)
    lng = -74.30 + 0.7 * rng.random(n_show)
    panels.append(("uniform-bbox: генератор, N до 10 млн", lat, lng))

    taxi = np.loadtxt(data_dir / "taxi-nyc.csv", delimiter=",", max_rows=2_000_000)
    idx = rng.choice(len(taxi), min(n_show, len(taxi)), replace=False)
    panels.append(("New York Taxi: выборка из 1.5 млн, сетка до 7.9 млн",
                   taxi[idx, 0], taxi[idx, 1]))

    poi = np.loadtxt(data_dir / "nyc-poi.csv", delimiter=",", max_rows=1_000_000)
    idx = rng.choice(len(poi), min(n_show, len(poi)), replace=False)
    panels.append(("OSM POI NYC: 870 тыс. точек", poi[idx, 0], poi[idx, 1]))

    geo_lat, geo_lng = [], []
    with (data_dir / "cities500.txt").open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.split("\t")
            if len(parts) < 6:
                continue
            try:
                geo_lat.append(float(parts[4]))
                geo_lng.append(float(parts[5]))
            except ValueError:
                continue
    idx = rng.choice(len(geo_lat), min(n_show, len(geo_lat)), replace=False)
    panels.append(("GeoNames cities500: 230 тыс. городов мира",
                   np.array(geo_lat)[idx], np.array(geo_lng)[idx]))

    fig, axes = plt.subplots(2, 2, figsize=(13, 10))
    for ax, (title, la, ln) in zip(axes.flat, panels):
        ax.scatter(ln, la, s=0.3, alpha=0.4, linewidths=0)
        ax.set_title(title, fontsize=11)
        ax.set_xlabel("долгота")
        ax.set_ylabel("широта")
    fig.suptitle("Распределение точек по наборам данных (выборка ~30 тыс. точек на панель)")
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(out / "datasets-distribution.png")
    plt.close(fig)
    print("distribution chart written")


def chart_tenm():
    """Single shared 2x2 panel for the 10M-point grid: LatLong / ECEF / S2."""
    if not (GRID / "tenm-knn.txt").exists():
        return
    own = {}
    for f in ("tenm-knn", "tenm-radius"):
        own.update(parse_jmh(GRID / f"{f}.txt"))
    s2 = {}
    for f in ("tenm-s2-knn", "tenm-s2-radius"):
        s2.update(parse_jmh(GRID / f"{f}.txt"))
    lucene = {}
    for f in ("tenm-lucene-knn", "tenm-lucene-radius"):
        fpath = GRID / f"{f}.txt"
        if fpath.exists():
            lucene.update(parse_jmh(fpath))

    series = [
        ("LatLong", own, "queryGeodesic", "#d62728", "-"),
        ("ECEF", own, "queryKdTree", "#9467bd", "-"),
        ("Google S2", s2, "queryS2", "#2ca02c", "--"),
        ("Lucene LatLonPoint", lucene, "queryLucene", "#17becf", ":"),
    ]
    panels = [
        ("knn", "10", "kNN, k = 10"),
        ("knn", "100", "kNN, k = 100"),
        ("radius", "1000", "Radius-поиск, r = 1000 м"),
        ("radius", "50000", "Radius-поиск, r = 50 000 м"),
    ]
    out = CHARTS / "tenm"
    out.mkdir(parents=True, exist_ok=True)
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    for ax, (fam, param, title) in zip(axes.flat, panels):
        for label, data, prefix, color, ls in series:
            method = prefix + ("Knn" if fam == "knn" else "Radius")
            xs = sorted(n for (m, n, p) in data if m == method and p == param)
            if not xs:
                continue
            ys = [data[(method, n, param)].mean for n in xs]
            lo = [data[(method, n, param)].err_lo for n in xs]
            hi = [data[(method, n, param)].err_hi for n in xs]
            ax.plot(xs, ys, marker="o", markersize=3, label=label, color=color, linestyle=ls, linewidth=1.6)
            ax.fill_between(xs, [y - l for y, l in zip(ys, lo)], [y + h for y, h in zip(ys, hi)],
                            color=color, alpha=0.13, linewidth=0)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("число точек в индексе, N")
        ax.set_ylabel("throughput, ops/s")
        ax.set_title(f"{title} (± ДИ)")
        ax.legend(fontsize=9)
    fig.tight_layout()
    fig.savefig(out / "tenm-2x2.png")
    plt.close(fig)
    print("tenm panel written")


def main() -> int:
    data = load_grid()
    chart_throughput(data)
    chart_static_comparison()
    chart_nodecost()
    chart_realdata()
    chart_realdata_2x2()
    chart_selectivity()
    chart_tenm()
    print(f"charts written to {CHARTS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
