#!/usr/bin/env python3
"""Builds measurement charts for the Geo KD-tree perf cycle.

Reads JMH text results (report/bench/grid/<state>-{knn,radius}.txt, optionally
public-smile-*.txt), BDN csv exports (lab-2-geo report/artifacts) and renders
throughput / speedup / ratio / comparison charts with confidence intervals into
report/charts/. Also emits markdown summary tables to report/bench/grid/tables.md.

Units: everything is throughput in ops/s. BDN reports per-operation time, so
ops/s = 1e6 / Mean_us; the time CI is carried over exactly by inverting the
bounds: [1e6/(Mean+Error), 1e6/(Mean-Error)]. Both JMH and BDN report a 99.9%
confidence interval by default, so bands are comparable.
"""

from __future__ import annotations

import argparse
import csv
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams.update({
    "font.size": 11,
    "axes.grid": True,
    "grid.alpha": 0.3,
    "axes.axisbelow": True,
    "figure.dpi": 110,
    "savefig.dpi": 200,
    "savefig.bbox": "tight",
})

STATE_ORDER = [
    "baseline",
    "perf2-pruning",
    "perf3-seamaware",
    "perf4-bestinsert",
    "perf5-aspace",
    "perf6-trigcache",
]
STATE_LABELS = {
    "baseline": "baseline",
    "perf2-pruning": "шаг 2: отсечение",
    "perf3-seamaware": "шаг 3: seam-aware",
    "perf4-bestinsert": "шаг 4: best-лист",
    "perf5-aspace": "шаг 5: a-space",
    "perf6-trigcache": "шаг 6 (финал)",
}
FAMILIES = [("radius", 100), ("radius", 5000), ("knn", 1), ("knn", 20)]
FAMILY_TITLES = {
    ("radius", 100): "Radius-поиск, r = 100 м",
    ("radius", 5000): "Radius-поиск, r = 5000 м",
    ("knn", 1): "kNN, k = 1",
    ("knn", 20): "kNN, k = 20",
}
METHODS = {
    ("radius", 100): ("queryKdTreeRadius", "queryFullScanRadius"),
    ("radius", 5000): ("queryKdTreeRadius", "queryFullScanRadius"),
    ("knn", 1): ("queryKdTreeKnn", "queryFullScanKnn"),
    ("knn", 20): ("queryKdTreeKnn", "queryFullScanKnn"),
}
CS_METHOD = {
    "queryKdTreeRadius": "QueryKdTreeRadius",
    "queryFullScanRadius": "QueryFullScanRadius",
    "queryKdTreeKnn": "QueryKdTreeKnn",
    "queryFullScanKnn": "QueryFullScanKnn",
}
COLORS = {
    "baseline": "#7f7f7f",
    "perf2-pruning": "#1f77b4",
    "perf3-seamaware": "#2ca02c",
    "perf4-bestinsert": "#9467bd",
    "perf5-aspace": "#ff7f0e",
    "perf6-trigcache": "#d62728",
    "smile": "#8c564b",
    "csharp": "#17becf",
}


@dataclass
class Measurement:
    mean: float          # ops/s
    err_lo: float        # ops/s below mean
    err_hi: float        # ops/s above mean

    @property
    def err(self) -> float:
        return max(self.err_lo, self.err_hi)


# ---------------------------------------------------------------------------
# Parsers
# ---------------------------------------------------------------------------

def parse_jmh(path: Path) -> dict[tuple[str, int, str], Measurement]:
    """-> {(method, n, param_name): Measurement}; params read from the header."""
    out: dict[tuple[str, int, str], Measurement] = {}
    header_params: list[str] = []
    row_re = re.compile(
        r"^(\S+)\s+((?:\S+\s+)*\S+)\s+(thrpt|avgt)\s+(\d+)\s+"
        r"([0-9.]+(?:[eE][-+]?\d+)?)\s+±\s+([0-9.]+(?:[eE][-+]?\d+)?)\s+(\S+)"
    )
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("Benchmark") and "(" in line:
            header_params = re.findall(r"\((\w+)\)", line)
            continue
        m = row_re.match(line.strip())
        if not m:
            continue
        name, params_str, _mode, _cnt, score, error, _units = m.groups()
        if ":" in name or not header_params:
            continue
        params = params_str.split()
        if len(params) != len(header_params):
            continue
        record = dict(zip(header_params, params))
        method = name.split(".")[-1]
        out[(method, int(record["n"]), record.get("k") or record.get("radiusMeters"))] = Measurement(
            float(score), float(error), float(error)
        )
    return out


def parse_bdn(path: Path) -> dict[tuple[str, int, str], Measurement]:
    """BDN csv -> ops/s with exactly inverted time CI."""
    out: dict[tuple[str, int, str], Measurement] = {}
    with path.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            mean_us = _parse_us(row["Mean"])
            err_us = _parse_us(row["Error"])
            n = int(row["N"])
            param = row.get("K") or row.get("RadiusMeters")
            hi = 1e6 / max(mean_us - err_us, 1e-12)
            lo = 1e6 / (mean_us + err_us)
            mid = 1e6 / mean_us
            out[(row["Method"], n, param)] = Measurement(mid, mid - lo, hi - mid)
    return out


def _parse_us(text: str) -> float:
    value, unit = text.strip().split()
    value = value.replace(",", "")
    mult = {"us": 1.0, "μs": 1.0, "ms": 1e3, "ns": 1e-3}[unit]
    return float(value) * mult


# ---------------------------------------------------------------------------
# Chart helpers
# ---------------------------------------------------------------------------

def _series(meas: dict[tuple[str, int, str], Measurement], method: str, param: str):
    xs = sorted(n for (meth, n, p) in meas if meth == method and p == param)
    ys = [meas[(method, n, param)].mean for n in xs]
    lo = [meas[(method, n, param)].err_lo for n in xs]
    hi = [meas[(method, n, param)].err_hi for n in xs]
    return xs, ys, lo, hi


def _plot_series(ax, xs, ys, lo, hi, label, color, linestyle="-", linewidth=1.8):
    ax.plot(xs, ys, marker="o", markersize=3.5, label=label, color=color,
            linestyle=linestyle, linewidth=linewidth)
    ax.fill_between(xs, [y - l for y, l in zip(ys, lo)],
                    [y + h for y, h in zip(ys, hi)], color=color, alpha=0.15, linewidth=0)


def _finish(ax, title, log_y=False):
    ax.set_xscale("log")
    if log_y:
        ax.set_yscale("log")
    ax.set_xlabel("число точек в индексе, N")
    ax.set_ylabel("throughput, ops/s")
    ax.set_title(title)
    ax.legend(fontsize=9)


# ---------------------------------------------------------------------------
# Chart sets
# ---------------------------------------------------------------------------

def chart_state_throughput(java: dict[str, dict], outdir: Path):
    out = outdir / "throughput"
    out.mkdir(parents=True, exist_ok=True)
    for family, param in FAMILIES:
        kd, _fs = METHODS[(family, param)]
        p = str(param)
        fig, ax = plt.subplots(figsize=(8, 5))
        for state in STATE_ORDER:
            if state not in java:
                continue
            xs, ys, lo, hi = _series(java[state], kd, p)
            _plot_series(ax, xs, ys, lo, hi, STATE_LABELS[state], COLORS[state])
        _finish(ax, f"{FAMILY_TITLES[(family, param)]} — состояния Java", log_y=True)
        fig.savefig(out / f"{family}-{param}.png")
        plt.close(fig)


def chart_speedup(java: dict[str, dict], outdir: Path):
    out = outdir / "speedup"
    out.mkdir(parents=True, exist_ok=True)
    for family, param in FAMILIES:
        kd, _fs = METHODS[(family, param)]
        p = str(param)
        base = java.get("baseline", {})
        fig, ax = plt.subplots(figsize=(8, 5))
        for state in STATE_ORDER:
            if state not in java or state == "baseline":
                continue
            xs, ys, lo, hi = _series(java[state], kd, p)
            bxs, bys, _blo, _bhi = _series(base, kd, p)
            base_map = dict(zip(bxs, bys))
            xs2 = [n for n in xs if n in base_map]
            sp = [y / base_map[n] for y, n in zip(ys, xs2)]
            splo = [l / base_map[n] for l, n in zip(lo, xs2)]
            sphi = [h / base_map[n] for h, n in zip(hi, xs2)]
            _plot_series(ax, xs2, sp, splo, sphi, STATE_LABELS[state], COLORS[state])
        ax.axhline(1.0, color="black", linewidth=0.8, linestyle=":")
        _finish(ax, f"{FAMILY_TITLES[(family, param)]} — ускорение к baseline")
        fig.savefig(out / f"{family}-{param}.png")
        plt.close(fig)


def chart_ratio(java: dict[str, dict], outdir: Path):
    out = outdir / "ratio"
    out.mkdir(parents=True, exist_ok=True)
    for family, param in FAMILIES:
        kd, fs = METHODS[(family, param)]
        p = str(param)
        fig, ax = plt.subplots(figsize=(8, 5))
        for state in STATE_ORDER:
            if state not in java:
                continue
            xs, ys, lo, hi = _series(java[state], kd, p)
            _fxs, fys, flo, fhi = _series(java[state], fs, p)
            fmap = dict(zip(_fxs, zip(fys, flo, fhi)))
            xs2 = [n for n in xs if n in fmap]
            ratio = [y / fmap[n][0] for y, n in zip(ys, xs2)]
            rlo = [l / fmap[n][1] for l, n in zip(lo, xs2)]
            rhi = [h / fmap[n][2] for h, n in zip(hi, xs2)]
            _plot_series(ax, xs2, ratio, rlo, rhi, STATE_LABELS[state], COLORS[state])
        ax.axhline(1.0, color="black", linewidth=0.8, linestyle=":")
        ax.set_ylabel("кратность к full-scan")
        _finish(ax, f"{FAMILY_TITLES[(family, param)]} — KD-tree против полного перебора")
        ax.set_ylabel("кратность к full-scan")
        fig.savefig(out / f"{family}-{param}.png")
        plt.close(fig)


def chart_java_vs_cs(java: dict[str, dict], cs: dict | None, outdir: Path):
    if not cs:
        return
    out = outdir / "java-vs-cs"
    out.mkdir(parents=True, exist_ok=True)
    for family, param in FAMILIES:
        kd, fs = METHODS[(family, param)]
        p = str(param)
        fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharex=True)
        for ax, method, subtitle in (
            (axes[0], kd, "KD-tree"),
            (axes[1], fs, "полный перебор"),
        ):
            for state in STATE_ORDER:
                if state not in java:
                    continue
                xs, ys, lo, hi = _series(java[state], method, p)
                if state not in ("baseline", "perf6-trigcache"):
                    ax.plot(xs, ys, color=COLORS[state], alpha=0.35, linewidth=1.2)
                    continue
                _plot_series(ax, xs, ys, lo, hi, f"Java: {STATE_LABELS[state]}", COLORS[state])
            cs_method = CS_METHOD[method]
            xs, ys, lo, hi = _series(cs, cs_method, p)
            _plot_series(ax, xs, ys, lo, hi, "C#", COLORS["csharp"], linestyle="--")
            _finish(ax, f"{subtitle} — Java против C#", log_y=True)
        fig.suptitle(FAMILY_TITLES[(family, param)], y=1.02)
        fig.savefig(out / f"{family}-{param}.png")
        plt.close(fig)

    out2 = outdir / "java-vs-cs-ratio"
    out2.mkdir(parents=True, exist_ok=True)
    for family, param in FAMILIES:
        kd, _fs = METHODS[(family, param)]
        p = str(param)
        fig, ax = plt.subplots(figsize=(8, 5))
        for state in ("baseline", "perf6-trigcache"):
            if state not in java:
                continue
            xs, ys, lo, hi = _series(java[state], kd, p)
            cxs, cys, clo, chi = _series(cs, CS_METHOD[kd], p)
            cmap = dict(zip(cxs, zip(cys, clo, chi)))
            xs2 = [n for n in xs if n in cmap]
            ratio = [y / cmap[n][0] for y, n in zip(ys, xs2)]
            rlo = [l / cmap[n][1] for l, n in zip(lo, xs2)]
            rhi = [h / cmap[n][2] for h, n in zip(hi, xs2)]
            _plot_series(ax, xs2, ratio, rlo, rhi, f"Java {STATE_LABELS[state]} / C#", COLORS[state])
        ax.axhline(1.0, color="black", linewidth=0.8, linestyle=":")
        ax.set_ylabel("отношение throughput, Java / C#")
        _finish(ax, f"{FAMILY_TITLES[(family, param)]} — Java/C# (KD-tree)")
        ax.set_ylabel("отношение throughput, Java / C#")
        fig.savefig(out2 / f"{family}-{param}.png")
        plt.close(fig)



def chart_summary(java: dict[str, dict], cs: dict | None, smile: dict | None, outdir: Path):
    out = outdir / "summary"
    out.mkdir(parents=True, exist_ok=True)
    smile_method = {"radius": "queryS2Radius", "knn": "queryS2Knn"}  # S2 as the dynamic competitor

    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    for ax, (family, param) in zip(axes.flat, FAMILIES):
        kd, _fs = METHODS[(family, param)]
        p = str(param)
        for state in STATE_ORDER:
            if state in java:
                xs, ys, lo, hi = _series(java[state], kd, p)
                _plot_series(ax, xs, ys, lo, hi, STATE_LABELS[state], COLORS[state])
        if cs:
            xs, ys, lo, hi = _series(cs, CS_METHOD[kd], p)
            _plot_series(ax, xs, ys, lo, hi, "C#", COLORS["csharp"], linestyle="--")
        if smile:
            xs, ys, lo, hi = _series(smile, smile_method[family], p)
            _plot_series(ax, xs, ys, lo, hi, "Google S2", COLORS["smile"], linestyle="--", linewidth=1.4)
        _finish(ax, FAMILY_TITLES[(family, param)], log_y=True)
    fig.tight_layout()
    fig.savefig(out / "summary-2x2.png")
    plt.close(fig)

    n_max = max((n for (m, n, p) in java["perf6-trigcache"] if m.startswith("queryKdTree")), default=None)
    if n_max is None:
        return

    def kd_method(family):
        return METHODS[(family, 100)][0] if family == "radius" else METHODS[(family, 1)][0]

    entries = []
    if cs:
        entries.append(("C#", cs, {fam: CS_METHOD[kd_method(fam)] for fam in ("radius", "knn")}))
    entries.append(("LatLong (baseline)", java.get("baseline"), {fam: kd_method(fam) for fam in ("radius", "knn")}))
    if smile:
        entries.append(("Google S2", smile, {"radius": "queryS2Radius", "knn": "queryS2Knn"}))
    entries.append(("ECEF (финал)", java.get("perf6-trigcache"), {fam: kd_method(fam) for fam in ("radius", "knn")}))

    color_of = {"C#": COLORS["csharp"], "LatLong (baseline)": COLORS["baseline"],
                "Google S2": COLORS["smile"], "ECEF (финал)": COLORS["perf6-trigcache"]}

    fig, ax = plt.subplots(figsize=(11, 5.5))
    width = 0.8 / max(len(entries), 1)
    for i, (family, param) in enumerate(FAMILIES):
        p = str(param)
        for j, (label, data, methods) in enumerate(entries):
            if not data:
                continue
            mm = data.get((methods[family], n_max, p))
            if not mm:
                continue
            x = i + j * width - 0.4 + width / 2
            ax.bar(x, mm.mean, width=width * 0.9, yerr=mm.err, capsize=3,
                   color=color_of[label], label=label if i == 0 else None)
    ax.set_xticks(range(len(FAMILIES)))
    ax.set_xticklabels([FAMILY_TITLES[f] for f in FAMILIES], fontsize=9)
    ax.set_yscale("log")
    ax.set_ylabel("throughput, ops/s")
    ax.set_xlabel("тип запроса")
    ax.set_title(f"Throughput при N = {n_max} — итоговое сравнение")
    ax.legend(fontsize=9)
    fig.savefig(out / "final-bar.png")
    plt.close(fig)


# ---------------------------------------------------------------------------
# Markdown summary tables
# ---------------------------------------------------------------------------

def fmt(v: float) -> str:
    return f"{v:,.0f}".replace(",", " ")


def write_tables(java, cs, smile, path: Path):
    lines = ["# Сводные таблицы замеров (ops/s, ± ДИ)", ""]
    for family, param in FAMILIES:
        kd, fs = METHODS[(family, param)]
        p = str(param)
        title = FAMILY_TITLES[(family, param)]
        lines.append(f"## {title}")
        lines.append("")
        states = [s for s in STATE_ORDER if s in java]
        for method, label in ((kd, "KD-tree"), (fs, "full-scan")):
            lines.append(f"### {label}")
            lines.append("")
            header = "| N | " + " | ".join(STATE_LABELS[s] for s in states)
            if cs:
                header += " | C#"
            if smile and method == kd:
                header += " | Smile"
            lines.append(header + " |")
            lines.append("|---|" + "---|" * (len(states) + (1 if cs else 0) + (1 if smile and method == kd else 0)))
            ns = sorted({n for (m, n, pp) in java[states[0]] if m == method and pp == p})
            for n in ns:
                row = f"| {n} "
                for s in states:
                    mm = java[s].get((method, n, p))
                    row += f"| {fmt(mm.mean)} ± {fmt(mm.err)} " if mm else "| — "
                if cs:
                    mm = cs.get((CS_METHOD[method], n, p))
                    row += f"| {fmt(mm.mean)} ± {fmt(mm.err)} " if mm else "| — "
                if smile and method == kd:
                    sm = {"radius": "querySmileRadius", "knn": "querySmileKnn"}[family]
                    mm = smile.get((sm, n, p))
                    row += f"| {fmt(mm.mean)} ± {fmt(mm.err)} " if mm else "| — "
                lines.append(row + "|")
            lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"tables written: {path}")


# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--grid-dir", default="report/bench/grid")
    ap.add_argument("--charts-dir", default="report/charts")
    ap.add_argument("--cs-dir", default=None,
                    help="lab-2-geo directory with report/artifacts BDN csv exports")
    args = ap.parse_args()

    grid = Path(args.grid_dir)
    charts = Path(args.charts_dir)
    charts.mkdir(parents=True, exist_ok=True)

    java: dict[str, dict] = {}
    for state in STATE_ORDER:
        files = sorted(grid.glob(f"{state}-*.txt"))
        merged: dict = {}
        for f in files:
            merged.update(parse_jmh(f))
        if merged:
            java[state] = merged
        else:
            print(f"warn: no data for state {state}")
    if not java:
        print("no Java data found", file=sys.stderr)
        return 1

    smile: dict | None = None
    smile_files = sorted(grid.glob("public-s2-*.txt"))
    if smile_files:
        smile = {}
        for f in smile_files:
            smile.update(parse_jmh(f))

    cs: dict | None = None
    if args.cs_dir:
        cs_dir = Path(args.cs_dir)
        cs = {}
        for name in ("Hw2.Benchmarks.GeoKdTreeKnnBenchmarks-report.csv",
                     "Hw2.Benchmarks.GeoKdTreeRadiusBenchmarks-report.csv"):
            f = cs_dir / "report/artifacts" / name
            if f.exists():
                cs.update(parse_bdn(f))
        if not cs:
            print("warn: C# csv not found")
            cs = None

    chart_state_throughput(java, charts)
    chart_speedup(java, charts)
    chart_ratio(java, charts)
    chart_java_vs_cs(java, cs, charts)
    chart_summary(java, cs, smile, charts)
    write_tables(java, cs, smile, grid / "tables.md")
    print(f"charts written to {charts}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
