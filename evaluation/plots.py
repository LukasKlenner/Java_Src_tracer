"""Plots of analyze_mutation_eval.py, saved to the run directory:
  mutation_scores.png    mutation score per benchmark and approach
  agreement.png          KeY vs. concrete run: share of traces per agreement cell (unmutated / mutants pooled)
  agreement_mutants.png  the same per mutant, sorted by the share of "only KeY"
  key_time.png           KeY auto-mode time per per-trace proof over its trace length
  trace_overlap.png      work of one shared proof over the prefix tree of a run's traces vs. separate proofs
  proof_modes.png        time to verdict per run: per-trace (key) vs. multi-trace (key-multi)
"""

import os
from statistics import median

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter

from analyze_mutation_eval import AGREEMENT_CELLS, AGREEMENT_LABELS, CONCRETE_LABELS, Agreement
from common import APPROACH_LABELS, APPROACHES, BASELINE

# reference categorical palette, slots 1-4 (light surface)
COLORS = {"crash": "#2a78d6", "key": "#eb6834", "runtime": "#1baf7a", "key-multi": "#eda100"}
SURFACE = "#fcfcfb"
TEXT_PRIMARY = "#0b0b0b"
TEXT_SECONDARY = "#52514e"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"

AGREEMENT_COLORS = {"both": COLORS["crash"], "key_only": COLORS["key"], "run_only": COLORS["runtime"],
                    "neither": AXIS}
PROOF_STYLES = {
    "closed": ("Closed", COLORS["crash"]),
    "open": ("Open goals (bug)", COLORS["key"]),
    "step_limit": ("Rule-application limit", COLORS["runtime"]),
}


def style_axes(ax, grid="y"):
    ax.set_facecolor(SURFACE)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(AXIS)
    ax.tick_params(colors=TEXT_SECONDARY, length=0)
    if "y" in grid:
        ax.yaxis.grid(True, color=GRID, linewidth=0.8)
    if "x" in grid:
        ax.xaxis.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def log_axes(ax):
    """Log-log axes with plain numbers at 1, 2, 5 x 10^n."""
    ax.set_xscale("log")
    ax.set_yscale("log")
    for axis in (ax.xaxis, ax.yaxis):
        axis.set_major_locator(LogLocator(subs=(1, 2, 5)))
        axis.set_major_formatter(FuncFormatter(lambda v, _: "%g" % v))
        axis.set_minor_formatter(NullFormatter())


def label(ax, xlabel=None, ylabel=None, title=None):
    if xlabel:
        ax.set_xlabel(xlabel, color=TEXT_SECONDARY)
    if ylabel:
        ax.set_ylabel(ylabel, color=TEXT_SECONDARY)
    if title:
        ax.set_title(title, color=TEXT_PRIMARY, loc="left")


def legend(fig_or_ax, handles=None, labels=None, **kwargs):
    args = (handles, labels) if handles is not None else ()
    fig_or_ax.legend(*args, frameon=False, labelcolor=TEXT_SECONDARY, fontsize=8, **kwargs)


def figure_legend(fig, axes, **kwargs):
    """One legend for all axes, without duplicate labels."""
    handles = {}
    for ax in axes:
        for h, l in zip(*ax.get_legend_handles_labels()):
            handles.setdefault(l, h)
    legend(fig, list(handles.values()), list(handles.keys()), **kwargs)


def save(fig, run_dir, file_name):
    out = os.path.join(run_dir, file_name)
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


# ---- mutation score ----

def plot_scores(reports, run_dir):
    approaches = [a for a in APPROACHES if any(a in r.bench.approaches for r in reports)]
    x = np.arange(len(reports))
    width = 0.8 / len(approaches)
    fig, ax = plt.subplots(figsize=(max(6.0, 2.2 * len(reports) + 2), 4.5), facecolor=SURFACE)
    style_axes(ax)
    for i, a in enumerate(approaches):
        values = [100 * next((s.score for s in r.summaries if s.approach == a), 0.0) for r in reports]
        bars = ax.bar(x + (i - (len(approaches) - 1) / 2) * width, values, width,
                      color=COLORS[a], edgecolor=SURFACE, linewidth=2, label=APPROACH_LABELS[a])
        for bar, v in zip(bars, values):
            ax.annotate("%.0f%%" % v, (bar.get_x() + bar.get_width() / 2, v), xytext=(0, 3),
                        textcoords="offset points", ha="center", va="bottom", fontsize=8, color=TEXT_SECONDARY)
    ax.set_xticks(x)
    ax.set_xticklabels(["%s\n(%d mutants)" % (r.bench.name, len(r.bench.valid)) for r in reports],
                       color=TEXT_PRIMARY)
    ax.set_ylim(0, 105)
    label(ax, ylabel="Mutants flagged as buggy (%)")
    legend(ax, loc="upper left", bbox_to_anchor=(0, -0.15), ncol=len(approaches))
    fig.tight_layout()
    save(fig, run_dir, "mutation_scores.png")


# ---- agreement ----

def draw_agreement_bars(ax, positions, shares, horizontal, height):
    """100% stacked bars, one per position; shares: list of {cell: share}. 'No KeY result' is dotted."""
    bar = ax.barh if horizontal else ax.bar
    base = np.zeros(len(positions))
    for cell in AGREEMENT_CELLS:
        values = np.array([100 * sh[cell] for sh in shares])
        style = ({"facecolor": SURFACE, "edgecolor": TEXT_SECONDARY, "hatch": "...", "linewidth": 0.8}
                 if cell == "no_result" else {"color": AGREEMENT_COLORS[cell], "edgecolor": SURFACE, "linewidth": 1.5})
        bar(positions, values, height, label=AGREEMENT_LABELS[cell], **{"left" if horizontal else "bottom": base},
            **style)
        base += values


def plot_agreement(reports, run_dir):
    bars = []  # (label, Agreement total)
    for r in reports:
        for name, rows in (("%s (unmutated)" % r.bench.name, [a for a in r.agreement if a.mutant == BASELINE]),
                           ("%s (%d mutants)" % (r.bench.name, len(r.bench.valid)),
                            [a for a in r.agreement if a.mutant != BASELINE])):
            if rows and (total := Agreement.total(rows)).traces:
                bars.append((name, total))
    if not bars:
        return
    fig, ax = plt.subplots(figsize=(8, 2.2 + 0.55 * len(bars)), facecolor=SURFACE)
    style_axes(ax, grid="x")
    ax.spines["bottom"].set_visible(False)
    y = np.arange(len(bars))[::-1]
    draw_agreement_bars(ax, y, [t.shares() for _, t in bars], horizontal=True, height=0.6)
    for yi, (_, t) in zip(y, bars):
        ax.annotate("n=%d" % t.traces, (100, yi), xytext=(4, 0), textcoords="offset points", va="center",
                    fontsize=8, color=TEXT_SECONDARY)
        sh = t.shares()
        if sh["key_only"] >= 0.04:  # direct label on the cell the argument rests on
            ax.annotate("%.0f%%" % (100 * sh["key_only"]), (100 * sh["both"] + 50 * sh["key_only"], yi),
                        ha="center", va="center", fontsize=8, color=TEXT_PRIMARY)
    ax.set_yticks(y)
    ax.set_yticklabels([name for name, _ in bars], color=TEXT_PRIMARY)
    ax.set_xlim(0, 100)
    label(ax, xlabel="Share of traces (%)",
          title="KeY vs. concrete run (%s) on the same inputs" % CONCRETE_LABELS[bars[0][1].concrete])
    legend(ax, loc="upper left", bbox_to_anchor=(0, -0.12), ncol=3)
    fig.tight_layout()
    save(fig, run_dir, "agreement.png")


def plot_agreement_mutants(reports, run_dir):
    panels = []
    for r in reports:
        per_mutant = {}
        for a in r.agreement:
            if a.mutant != BASELINE:
                per_mutant.setdefault(a.mutant, []).append(a)
        bars = [(m, t.shares()) for m, rows in per_mutant.items() if (t := Agreement.total(rows)).traces]
        bars.sort(key=lambda b: (-b[1]["key_only"], -b[1]["both"], int(b[0])))
        if bars:
            panels.append((r.bench.name, bars))
    if not panels:
        return
    widest = max(len(b) for _, b in panels)
    fig, axes = plt.subplots(len(panels), 1, figsize=(max(7.0, 0.32 * widest + 2), 3.4 * len(panels)),
                             facecolor=SURFACE, squeeze=False)
    for ax, (name, bars) in zip(axes[:, 0], panels):
        style_axes(ax)
        x = np.arange(len(bars))
        draw_agreement_bars(ax, x, [sh for _, sh in bars], horizontal=False, height=0.8)
        ax.set_xticks(x)
        ax.set_xticklabels([m for m, _ in bars], color=TEXT_PRIMARY, fontsize=7)
        ax.set_xlim(-0.6, len(bars) - 0.4)
        ax.set_ylim(0, 100)
        label(ax, "Mutant (sorted by share of \"only KeY\")", "Share of traces (%)", name)
    legend(axes[-1, 0], loc="upper left", bbox_to_anchor=(0, -0.25), ncol=3)
    fig.tight_layout()
    save(fig, run_dir, "agreement_mutants.png")


# ---- KeY time ----

def plot_key_time(reports, run_dir):
    """Auto-mode time over trace length (log-log), one panel per benchmark. Timeouts have no auto-mode time; they are
    drawn as hollow markers at --key-timeout."""
    reports = [r for r in reports if r.proofs]
    if not reports:
        return
    fig, axes = plt.subplots(1, len(reports), figsize=(max(6.0, 4.2 * len(reports)), 4.4), facecolor=SURFACE,
                             squeeze=False, sharey=True)
    for ax, r in zip(axes[0], reports):
        style_axes(ax, grid="xy")
        for status, (name, color) in PROOF_STYLES.items():
            pts = [(n, t) for n, t, st in r.proofs if st == status and t is not None]
            if pts:
                ax.scatter([n for n, _ in pts], [max(t, 1e-3) for _, t in pts], s=22, color=color,
                           edgecolors=SURFACE, linewidths=0.8, alpha=0.85, label=name, zorder=3)
        timeouts = [n for n, _, st in r.proofs if st == "key_timeout"]
        limit = r.bench.parameters.get("key_timeout")
        if timeouts and limit:
            ax.axhline(limit, color=TEXT_SECONDARY, linewidth=0.8, linestyle="--", zorder=2)
            ax.scatter(timeouts, [limit] * len(timeouts), s=26, facecolors="none", edgecolors=TEXT_SECONDARY,
                       linewidths=1.0, label="KeY timeout (at --key-timeout)", zorder=3)
        log_axes(ax)
        label(ax, xlabel="Trace elements", title="%s (%d proofs)" % (r.bench.name, len(r.proofs)))
    label(axes[0, 0], ylabel="KeY auto-mode time (s)")
    figure_legend(fig, axes[0], loc="lower left", ncol=4)
    fig.tight_layout(rect=(0, 0.08, 1, 1))
    save(fig, run_dir, "key_time.png")


# ---- trace overlap ----

def plot_trace_overlap(reports, run_dir):
    """Left: per key run on a mutant, prefix tree nodes relative to the elements of all / distinct traces (lower = more
    work a shared proof saves). Right: per distinct trace, the share of its prefix shared with another trace."""
    rows = []
    for r in reports:
        runs = [o for o in r.overlap if o.mutant != BASELINE]
        if runs:
            rows.append(("%s\n(%d runs, %d traces,\n%d distinct)" % (
                r.bench.name, len(runs), sum(o.traces for o in runs), sum(o.distinct_traces for o in runs)), runs))
    if not rows:
        return
    rng = np.random.default_rng(0)
    fig, axes = plt.subplots(1, 2, figsize=(12, 1.6 + 1.3 * len(rows)), facecolor=SURFACE, sharey=True)
    for ax in axes:
        style_axes(ax, grid="x")
        ax.spines["bottom"].set_visible(False)
        ax.set_xlim(-2, 102)

    def strip(ax, yi, values, color, name=None, median_above=True):
        ax.scatter(values, yi + rng.uniform(-0.12, 0.12, len(values)), s=22, color=color, edgecolors=SURFACE,
                   linewidths=0.8, alpha=0.8, zorder=3, label=name)
        med = median(values)
        ax.plot([med, med], [yi - 0.17, yi + 0.17], color=TEXT_PRIMARY, linewidth=2, zorder=4)
        ax.annotate("median %.0f%%" % med, (med, yi + (0.17 if median_above else -0.17)),
                    xytext=(0, 2 if median_above else -2), textcoords="offset points", ha="center",
                    va="bottom" if median_above else "top", fontsize=8, color=TEXT_PRIMARY)

    y = np.arange(len(rows))[::-1]
    variants = [("elements", "vs. one proof per trace", COLORS["key"], 0.2),
                ("elements_distinct", "vs. one proof per distinct trace", COLORS["crash"], -0.2)]
    for yi, (_, runs) in zip(y, rows):
        pooled = []
        for attr, name, color, dy in variants:
            strip(axes[0], yi + dy, [100.0 * o.prefix_tree_nodes / getattr(o, attr) for o in runs], color,
                  name=name if yi == y[0] else None, median_above=dy > 0)
            pooled.append("%.0f%%" % (100.0 * sum(o.prefix_tree_nodes for o in runs)
                                      / sum(getattr(o, attr) for o in runs)))
        axes[0].annotate("all runs:\n" + "\n".join(pooled), (1, yi), xycoords=("axes fraction", "data"),
                         xytext=(-2, 0), textcoords="offset points", ha="right", va="center", fontsize=8,
                         color=TEXT_SECONDARY)
        strip(axes[1], yi, [100.0 * s for o in runs for s in o.shared_prefix], COLORS["key"])
    label(axes[0], "Elements in the prefix tree (% of the elements of separate proofs)",
          title="Work of one shared proof per mutant")
    label(axes[1], "Prefix shared with another distinct trace (% of the trace)", title="Overlap per distinct trace")
    axes[0].set_yticks(y)
    axes[0].set_yticklabels([name for name, _ in rows], color=TEXT_PRIMARY)
    axes[0].set_ylim(-0.6, len(rows) - 0.4)
    legend(fig, *axes[0].get_legend_handles_labels(), loc="lower left", ncol=2)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    save(fig, run_dir, "trace_overlap.png")


# ---- per-trace vs. multi-trace ----

def plot_proof_modes(reports, run_dir):
    """Time to verdict per (mutant, seed), per-trace on x, multi-trace on y (log-log). Below the diagonal the
    multi-trace proof decides faster. Runs where the two disagree on the bug are hollow."""
    reports = [r for r in reports if r.proof_modes]
    if not reports:
        return
    groups = [("Both find a bug", lambda p: p.per_bug and p.multi_bug, {"color": COLORS["key"]}),
              ("Both find none", lambda p: not p.per_bug and not p.multi_bug, {"color": COLORS["crash"]}),
              ("Verdicts differ", lambda p: p.per_bug != p.multi_bug,
               {"facecolors": "none", "edgecolors": TEXT_PRIMARY, "linewidths": 1.0})]
    fig, axes = plt.subplots(1, len(reports), figsize=(max(5.0, 4.4 * len(reports)), 4.8), facecolor=SURFACE,
                             squeeze=False)
    for ax, r in zip(axes[0], reports):
        style_axes(ax, grid="xy")
        pairs = r.proof_modes
        lo = max(min(min(p.per_verdict_s, p.multi_verdict_s) for p in pairs) / 1.5, 0.1)
        hi = max(max(p.per_verdict_s, p.multi_verdict_s) for p in pairs) * 1.5
        ax.plot([lo, hi], [lo, hi], color=AXIS, linewidth=1, zorder=2)
        for name, select, style in groups:
            pts = [p for p in pairs if select(p)]
            if pts:
                if "color" in style:
                    style = {**style, "edgecolors": SURFACE, "linewidths": 0.8}
                ax.scatter([max(p.per_verdict_s, lo) for p in pts], [max(p.multi_verdict_s, lo) for p in pts], s=26,
                           alpha=0.85, zorder=3, label=name, **style)
        log_axes(ax)
        ax.set_xlim(lo, hi)
        ax.set_ylim(lo, hi)
        ax.set_aspect("equal")
        label(ax, "Per-trace: time to verdict (s)", title="%s (%d runs)" % (r.bench.name, len(pairs)))
    label(axes[0, 0], ylabel="Multi-trace: time to verdict (s)")
    figure_legend(fig, axes[0], loc="lower left", ncol=3)
    fig.text(0.99, 0.02, "Verdict: first open proof, else last proof finished. Below the diagonal: multi-trace faster.",
             ha="right", fontsize=8, color=TEXT_SECONDARY)
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    save(fig, run_dir, "proof_modes.png")


def plot_all(reports, run_dir):
    for plot in (plot_scores, plot_agreement, plot_agreement_mutants, plot_key_time, plot_trace_overlap,
                 plot_proof_modes):
        plot(reports, run_dir)
    plt.show()
