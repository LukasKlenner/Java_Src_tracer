#!/usr/bin/env python3
"""Analyze common prefix lengths among traces in fuzzer runs.

For each run, computes pairwise common prefix lengths between all traces
and reports statistics (min, max, mean, median) plus a heatmap.

Usage:
    python trace_prefix_overlap.py                          # all runs in fuzz-out/
    python trace_prefix_overlap.py <run-dir> [<run-dir>…]   # specific run(s)
"""
import argparse
import os
import re
import sys

import matplotlib.pyplot as plt
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
FUZZ_OUT = os.path.join(PROJECT_ROOT, "fuzz-out")


def common_prefix_len(a, b):
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def load_traces(traces_dir):
    traces = {}
    for fname in sorted(os.listdir(traces_dir)):
        if not fname.endswith(".trace.txt"):
            continue
        path = os.path.join(traces_dir, fname)
        with open(path, "r") as f:
            traces[fname.removesuffix(".trace.txt")] = f.read().strip()
    return traces


def analyze_run(run_dir):
    traces_dir = os.path.join(run_dir, "traces")
    if not os.path.isdir(traces_dir):
        return None

    traces = load_traces(traces_dir)
    if len(traces) < 2:
        return None

    names = list(traces.keys())
    n = len(names)
    matrix = np.zeros((n, n), dtype=int)

    for i in range(n):
        for j in range(i, n):
            plen = common_prefix_len(traces[names[i]], traces[names[j]])
            matrix[i][j] = plen
            matrix[j][i] = plen

    pairwise = []
    for i in range(n):
        for j in range(i + 1, n):
            pairwise.append(matrix[i][j])

    lengths = np.array([len(traces[name]) for name in names])
    pairwise = np.array(pairwise)

    run_name = re.sub(r"_\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}$", "",
                       os.path.basename(run_dir))

    return {
        "run_name": run_name,
        "names": names,
        "matrix": matrix,
        "pairwise": pairwise,
        "lengths": lengths,
        "n": n,
    }


def print_stats(result):
    pw = result["pairwise"]
    lengths = result["lengths"]
    n = result["n"]

    ratios = []
    names = result["names"]
    matrix = result["matrix"]
    for i in range(n):
        for j in range(i + 1, n):
            shorter = min(lengths[i], lengths[j])
            if shorter > 0:
                ratios.append(matrix[i][j] / shorter)
    ratios = np.array(ratios)

    print(f"\n{'=' * 60}")
    print(f"  {result['run_name']}  ({n} traces)")
    print(f"{'=' * 60}")
    print(f"  Trace lengths:   min={lengths.min()}  max={lengths.max()}  "
          f"mean={lengths.mean():.1f}  median={np.median(lengths):.0f}")
    print(f"  Prefix lengths:  min={pw.min()}  max={pw.max()}  "
          f"mean={pw.mean():.1f}  median={np.median(pw):.0f}")
    print(f"  Prefix/shorter:  min={ratios.min():.1%}  max={ratios.max():.1%}  "
          f"mean={ratios.mean():.1%}  median={np.median(ratios):.1%}")


def plot_results(results):
    n_runs = len(results)
    fig, axes = plt.subplots(1, n_runs, figsize=(6 * n_runs, 5), squeeze=False)

    for idx, result in enumerate(results):
        ax = axes[0][idx]
        matrix = result["matrix"]
        n = result["n"]

        short_names = [name[:8] for name in result["names"]]
        im = ax.imshow(matrix, cmap="YlOrRd", aspect="equal")
        ax.set_xticks(range(n))
        ax.set_yticks(range(n))
        ax.set_xticklabels(short_names, rotation=90, fontsize=6)
        ax.set_yticklabels(short_names, fontsize=6)
        ax.set_title(f"{result['run_name']}\n({n} traces)", fontsize=11)
        fig.colorbar(im, ax=ax, label="Common prefix length", shrink=0.8)

    fig.suptitle("Pairwise Common Prefix Lengths", fontsize=14, y=1.02)
    fig.tight_layout()

    out_path = os.path.join(SCRIPT_DIR, "trace_prefix_overlap.png")
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    print(f"\nSaved heatmap to {out_path}")
    plt.show()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="*",
                        help="Run directories (default: all in fuzz-out/)")
    parser.add_argument("--no-plot", action="store_true",
                        help="Only print statistics, skip heatmap")
    args = parser.parse_args()

    if args.runs:
        run_dirs = [os.path.abspath(r) for r in args.runs]
    else:
        if not os.path.isdir(FUZZ_OUT):
            sys.exit(f"fuzz-out directory not found at {FUZZ_OUT}")
        run_dirs = sorted(
            os.path.join(FUZZ_OUT, d)
            for d in os.listdir(FUZZ_OUT)
            if os.path.isdir(os.path.join(FUZZ_OUT, d, "traces"))
        )
        if not run_dirs:
            sys.exit("No runs with traces found in fuzz-out/")

    results = []
    for run_dir in run_dirs:
        result = analyze_run(run_dir)
        if result is None:
            print(f"Skipping {os.path.basename(run_dir)}: no traces or < 2 traces")
            continue
        print_stats(result)
        results.append(result)

    if not results:
        sys.exit("No data to analyze")

    if not args.no_plot:
        plot_results(results)


if __name__ == "__main__":
    main()
