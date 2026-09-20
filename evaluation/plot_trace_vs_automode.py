#!/usr/bin/env python3
"""Plot input length and trace length vs. KeY automode time for fuzzer runs.

Fits linear, quadratic, and exponential models and reports R² for each.
Three subplots:
  1. Input length (bytes) vs. Automode time — the controlled independent variable
  2. Trace length vs. Automode time — shows symbolic execution cost growth
  3. Log-log scale of input length vs. Automode time — reveals power-law relationships

Usage:
    python plot_trace_vs_automode.py                          # all runs in fuzz-out/
    python plot_trace_vs_automode.py <run-dir> [<run-dir>…]   # specific run(s)
"""
import argparse
import os
import re
import sys
import glob
import matplotlib.pyplot as plt
import numpy as np
from scipy.optimize import curve_fit

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
FUZZ_OUT = os.path.join(PROJECT_ROOT, "fuzz-out")


def r_squared(y_true, y_pred):
    ss_res = np.sum((y_true - y_pred) ** 2)
    ss_tot = np.sum((y_true - np.mean(y_true)) ** 2)
    if ss_tot == 0:
        return 1.0
    return 1 - ss_res / ss_tot


def exp_func(x, a, b, c):
    return a * np.exp(b * x) + c


def collect_data(run_dir):
    key_inputs = os.path.join(run_dir, "key-inputs")
    corpus = os.path.join(run_dir, "corpus")
    if not os.path.isdir(key_inputs):
        return [], [], []

    input_lengths = []
    trace_lengths = []
    automode_times_ms = []

    for key_dir in sorted(glob.glob(os.path.join(key_inputs, "key-input-*"))):
        entry_name = os.path.basename(key_dir).removeprefix("key-input-")

        corpus_file = os.path.join(corpus, entry_name)
        if not os.path.isfile(corpus_file):
            continue
        input_len = os.path.getsize(corpus_file)

        trace_file = os.path.join(key_dir, f"{entry_name}.trace.txt")
        if not os.path.isfile(trace_file):
            continue
        with open(trace_file, "r") as f:
            trace_len = len(f.read().strip())

        csv_files = glob.glob(os.path.join(key_dir, "*.JML*.csv"))
        if not csv_files:
            continue

        automode_ms = None
        with open(csv_files[0], "r") as f:
            for line in f:
                m = re.match(r"Automode time;(\d+)ms", line.strip())
                if m:
                    automode_ms = int(m.group(1))
        if automode_ms is None:
            continue

        input_lengths.append(input_len)
        trace_lengths.append(trace_len)
        automode_times_ms.append(automode_ms)

    return input_lengths, trace_lengths, automode_times_ms


def fit_and_plot(x, y, ax, color, run_name):
    x_fit = np.linspace(x.min(), x.max(), 300)

    z1 = np.polyfit(x, y, 1)
    p1 = np.poly1d(z1)
    r2_lin = r_squared(y, p1(x))
    ax.plot(x_fit, p1(x_fit), "--", color=color, alpha=0.5,
            label=f"Linear  R²={r2_lin:.3f}")

    z2 = np.polyfit(x, y, 2)
    p2 = np.poly1d(z2)
    r2_quad = r_squared(y, p2(x))
    ax.plot(x_fit, p2(x_fit), "-.", color=color, alpha=0.5,
            label=f"Quadratic  R²={r2_quad:.3f}")

    r2_exp = None
    try:
        popt, _ = curve_fit(exp_func, x, y,
                            p0=[0.1, 0.005, 0.5], maxfev=10000)
        y_exp = exp_func(x, *popt)
        r2_exp = r_squared(y, y_exp)
        ax.plot(x_fit, exp_func(x_fit, *popt), ":", color=color,
                alpha=0.7, linewidth=2,
                label=f"Exponential  R²={r2_exp:.3f}")
    except RuntimeError:
        print(f"  {run_name}: exponential fit did not converge")

    r2_best = max(filter(None, [r2_lin, r2_quad, r2_exp]))
    best = {r2_lin: "linear", r2_quad: "quadratic"}.get(r2_best)
    if best is None:
        best = "exponential"

    return r2_lin, r2_quad, r2_exp, best


def plot_run(run_dir, ax_input, ax_trace=None, ax_log=None, simple=False):
    run_name = re.sub(r"_\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}$", "",
                       os.path.basename(run_dir))
    input_lengths, trace_lengths, automode_times_ms = collect_data(run_dir)

    if not input_lengths:
        print(f"  {run_name}: no data, skipping")
        return

    input_len = np.array(input_lengths, dtype=float)
    trace_len = np.array(trace_lengths, dtype=float)
    automode_s = np.array(automode_times_ms) / 1000.0

    order = np.argsort(input_len)
    input_len = input_len[order]
    trace_len = trace_len[order]
    automode_s = automode_s[order]

    color = ax_input.scatter(input_len, automode_s, s=50, zorder=5,
                             label=run_name).get_facecolor()[0]

    print(f"  {run_name}: {len(input_len)} points")

    if not simple:
        r2_lin, r2_quad, r2_exp, best = fit_and_plot(
            input_len, automode_s, ax_input, color, run_name)
        print(f"    Input→Time:  R² lin={r2_lin:.3f}  quad={r2_quad:.3f}"
              f"  exp={'%.3f' % r2_exp if r2_exp else 'n/a'}  → best: {best}")

    if ax_trace is not None:
        order_t = np.argsort(trace_len)
        ax_trace.scatter(trace_len[order_t], automode_s[order_t], s=50,
                         color=color, zorder=5, label=run_name)
        if not simple:
            r2_lin, r2_quad, r2_exp, best = fit_and_plot(
                trace_len[order_t], automode_s[order_t], ax_trace, color, run_name)
            print(f"    Trace→Time:  R² lin={r2_lin:.3f}  quad={r2_quad:.3f}"
                  f"  exp={'%.3f' % r2_exp if r2_exp else 'n/a'}  → best: {best}")

    if ax_log is not None:
        mask = (input_len > 0) & (automode_s > 0)
        ax_log.scatter(input_len[mask], automode_s[mask], s=50, color=color,
                       zorder=5, label=run_name)
        if not simple and mask.sum() >= 2:
            log_z = np.polyfit(np.log10(input_len[mask]), np.log10(automode_s[mask]), 1)
            log_p = np.poly1d(log_z)
            log_x_fit = np.linspace(np.log10(input_len[mask].min()),
                                    np.log10(input_len[mask].max()), 200)
            ax_log.plot(10 ** log_x_fit, 10 ** log_p(log_x_fit),
                        "--", color=color, alpha=0.6,
                        label=f"slope={log_z[0]:.2f} (power law)")
            print(f"    Log-log slope: {log_z[0]:.2f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="*",
                        help="Run directories (default: all in fuzz-out/)")
    parser.add_argument("--simple", action="store_true",
                        help="Only show scatter plots without curve fitting")
    parser.add_argument("--input-only", action="store_true",
                        help="Only show the input length vs. automode time plot")
    args = parser.parse_args()

    if args.runs:
        run_dirs = [os.path.abspath(r) for r in args.runs]
    else:
        if not os.path.isdir(FUZZ_OUT):
            sys.exit(f"fuzz-out directory not found at {FUZZ_OUT}")
        run_dirs = sorted(
            os.path.join(FUZZ_OUT, d)
            for d in os.listdir(FUZZ_OUT)
            if os.path.isdir(os.path.join(FUZZ_OUT, d, "key-inputs"))
        )
        if not run_dirs:
            sys.exit("No runs with key-inputs found in fuzz-out/")

    if args.input_only:
        fig, ax_input = plt.subplots(1, 1, figsize=(8, 6))
        ax_trace = None
        ax_log = None
    else:
        fig, (ax_input, ax_trace, ax_log) = plt.subplots(1, 3, figsize=(20, 6))

    print("Collecting data…")
    for run_dir in run_dirs:
        plot_run(run_dir, ax_input, ax_trace, ax_log, simple=args.simple)

    ax_input.set_xlabel("Iteration Count", fontsize=12)
    ax_input.set_ylabel("KeY Automode time (s)", fontsize=12)
    #ax_input.set_title("Input Length vs. Automode Time", fontsize=13)
    ax_input.legend(fontsize=8)
    ax_input.grid(True, alpha=0.3)

    if ax_trace is not None:
        ax_trace.set_xlabel("Trace length (characters)", fontsize=12)
        ax_trace.set_ylabel("KeY Automode time (s)", fontsize=12)
        #ax_trace.set_title("Trace Length vs. Automode Time", fontsize=13)
        ax_trace.legend(fontsize=8)
        ax_trace.grid(True, alpha=0.3)

    if ax_log is not None:
        ax_log.set_xscale("log")
        ax_log.set_yscale("log")
        ax_log.set_xlabel("Input length (log, bytes)", fontsize=12)
        ax_log.set_ylabel("KeY Automode time (log, s)", fontsize=12)
        #ax_log.set_title("Log-Log Scale (power-law check)", fontsize=13)
        ax_log.legend(fontsize=9)
        ax_log.grid(True, alpha=0.3, which="both")

    fig.tight_layout()

    out_path = os.path.join(SCRIPT_DIR, "trace_vs_automode.png")
    fig.savefig(out_path, dpi=150)
    print(f"Saved plot to {out_path}")
    plt.show()


if __name__ == "__main__":
    main()
