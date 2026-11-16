"""
Analyze the results of run_mutation_eval.py.

For every benchmark and approach (crash, key, runtime) this reports
  - the mutation score: killed mutants / valid mutants, averaged over seeds
  - how many runs hit the wall-clock limit or failed, and KeY step-limit/timeout counts
  - the overlap of the kill sets (a mutant counts as killed by an approach if any seed killed it):
    killed only by key, only by runtime, by both, by neither, and by crash-only fuzzing
  - for the key approach: the time until KeY found the first bug (first proof with open goals) and the
    number of traces on which KeY found a bug / ran into its timeout
  - per mutant (mean over seeds): the traces of the key run (corpus entries reaching the method under test),
    the traces on which KeY found a bug or timed out, and the corpus entries that fail on the concrete run
    (runtime asserts, or crashes if the runtime approach was not run) - all approaches on a mutant replay the
    corpus Jazzer built by fuzzing that mutant
  - the mutants on which KeY found a bug although Jazzer, fuzzing the mutant with the asserts, found none
  - per corpus entry, the agreement of KeY and the concrete run on the same input (needs the key approach; the
    concrete run is the runtime approach, or the key run's own crash oracle without it):
      both     KeY finds open goals and the concrete run fails
      KeY only KeY finds open goals, the concrete run passes (KeY generalizes along the path - or a false alarm)
      run only KeY closes the proof, but the concrete run fails (inconsistent: contract and asserts disagree,
               overflow, or a tracing/retracing bug - should not happen)
      neither  KeY closes the proof and the concrete run passes
      no KeY result  KeY step limit, timeout or error
    entries not reaching the method under test and entries missing in one of the runs are counted separately
  - the surviving mutants per approach, with operator, line and transformation from mutants.csv

Reads the latest generate_mutants.py run in evaluation/mutation-out (or the one given with --run).

Plots, summary.csv and per_mutant.csv (saved to the run directory evaluation/mutation-out/mutation_<timestamp>/):
  mutation_scores.png   mutation score per benchmark and approach
  agreement.png         share of traces per agreement cell, per benchmark (mutants pooled, and the unmutated
                        program, where every open goal is a false alarm)
  agreement_mutants.png the same per mutant, sorted by the share of "KeY only"
  agreement.csv         the agreement counts per benchmark, mutant and seed
  key_time.png          KeY auto-mode time per proof over the number of trace elements, one panel per benchmark
                        (key runs of the unmutated program and all mutants; KeY timeouts drawn at --key-timeout);
                        the trace elements are counted in the traces/ of the key runs (deleted by --cleanup)
  trace_overlap.png     how much the traces of a key run overlap, i.e. whether one shared proof over the prefix tree
                        of the traces of a mutant would save work compared with one proof per trace from the start:
                        per key run (mutant, seed) the trace elements in the prefix tree / summed over all traces and
                        / summed over the distinct traces (the gain beyond skipping duplicate traces), and per distinct
                        trace the share of its prefix that it shares with another distinct trace of the same run
  trace_overlap.csv     the same per benchmark, mutant and seed (traces, distinct traces, elements, prefix-tree nodes)

Usage:
  python analyze_mutation_eval.py                   # all benchmarks with a results.csv
  python analyze_mutation_eval.py Max Quicksort
  python analyze_mutation_eval.py Max --survivors   # list surviving mutants
  python analyze_mutation_eval.py --wall-limit-killed --no-plot
  python analyze_mutation_eval.py --run mutation_2026-11-01_17.23.31
"""

import argparse
import csv
import json
import os
import re
import sys
from collections import defaultdict
from statistics import median

from generate_mutants import resolve_run_dir

APPROACHES = ["crash", "key", "runtime"]
APPROACH_LABELS = {
    "crash": "Jazzer (crashes only)",
    "key": "Jazzer + KeY",
    "runtime": "Jazzer + runtime asserts",
}
BASELINE = "original"

# Reference categorical palette, slots 1-3 (validated all-pairs, light surface)
COLORS = {"crash": "#2a78d6", "key": "#eb6834", "runtime": "#1baf7a"}
SURFACE = "#fcfcfb"
TEXT_PRIMARY = "#0b0b0b"
TEXT_SECONDARY = "#52514e"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"


def concrete_oracle(approaches):
    """The approach whose replay is the concrete run compared with KeY: the runtime asserts, or the key run's own
    crash oracle if the runtime approach was not run."""
    return "runtime" if "runtime" in approaches else "key"


CONCRETE_LABELS = {"runtime": "runtime asserts", "key": "crashes"}


def mean(values, empty=None):
    return sum(values) / len(values) if values else empty


def load_results(run_dir, name):
    path = os.path.join(run_dir, name, "results.csv")
    with open(path, encoding="utf-8", newline="") as f:
        return [r for r in csv.DictReader(f) if r["mutant"] != BASELINE]


def load_mutants(run_dir, name):
    path = os.path.join(run_dir, name, "mutants.csv")
    with open(path, encoding="utf-8", newline="") as f:
        return {r["id"]: r for r in csv.DictReader(f)}


def is_killed(row, wall_limit_killed):
    return row["status"] == "killed" or (wall_limit_killed and row["status"] == "wall_limit")


def analyze(name, args):
    rows = load_results(args.run_dir, name)
    mutants = load_mutants(args.run_dir, name)
    valid = sorted((m for m, r in mutants.items() if r["status"] == "valid"), key=int)
    approaches = [a for a in APPROACHES if any(r["approach"] == a for r in rows)]

    # (approach, seed) -> {mutant: row}
    runs = defaultdict(dict)
    for r in rows:
        runs[(r["approach"], r["seed"])][r["mutant"]] = r
    seeds = sorted({r["seed"] for r in rows}, key=lambda s: int(s) if s.isdigit() else s)

    summary = {}
    kill_sets = {}
    for a in approaches:
        scores, n_wall, n_error, n_missing = [], 0, 0, 0
        step_limit = key_timeout = 0
        killed_any = set()
        for s in seeds:
            by_mutant = runs.get((a, s), {})
            killed = {m for m in valid if m in by_mutant and is_killed(by_mutant[m], args.wall_limit_killed)}
            killed_any |= killed
            evaluated = [m for m in valid if m in by_mutant]
            n_missing += len(valid) - len(evaluated)
            if evaluated:
                scores.append(len(killed) / len(valid))
            for m in evaluated:
                r = by_mutant[m]
                n_wall += r["status"] == "wall_limit"
                n_error += r["status"] == "error"
                step_limit += int(r["key_step_limit"] or 0) > 0
                key_timeout += int(r["key_timeout"] or 0) > 0
        kill_sets[a] = killed_any
        key_times, key_open, key_timeouts = [], [], []
        if a == "key":
            for s in seeds:
                evaluated = [runs[(a, s)][m] for m in valid if m in runs.get((a, s), {})]
                if not evaluated:
                    continue
                key_open.append(sum(int(r["key_open"] or 0) for r in evaluated))
                key_timeouts.append(sum(int(r["key_timeout"] or 0) for r in evaluated))
                key_times += [float(r["time_to_key_s"]) for r in evaluated if r.get("time_to_key_s")]
        summary[a] = {
            "score": mean(scores, 0.0),
            "score_min": min(scores) if scores else 0.0,
            "score_max": max(scores) if scores else 0.0,
            "killed_any": len(killed_any),
            "wall_limit": n_wall,
            "error": n_error,
            "missing": n_missing,
            "step_limit_runs": step_limit,
            "key_timeout_runs": key_timeout,
            # per run: seconds until KeY found the first bug
            "key_times": sorted(key_times),
            # traces (summed over all mutants) on which KeY found a bug / timed out, mean over seeds
            "key_open_traces": mean(key_open, 0.0),
            "key_timeout_traces": mean(key_timeouts, 0.0),
        }

    per_mutant = mutant_traces(runs, valid, seeds, approaches)
    agreement = load_agreement(args.run_dir, name, valid, seeds, approaches)
    proofs = load_proofs(args.run_dir, name, valid, seeds) if "key" in approaches else []
    overlap = load_overlap(args.run_dir, name, valid, seeds) if "key" in approaches else []
    print_summary(name, valid, mutants, seeds, approaches, summary, kill_sets, args)
    print_beyond_fuzzer(runs, valid, seeds)
    print_mutant_traces(per_mutant)
    print_agreement(agreement)
    print_overlap(overlap)
    return {"name": name, "valid": len(valid), "approaches": approaches, "summary": summary,
            "runs": runs, "valid_ids": valid, "seeds": seeds, "per_mutant": per_mutant, "agreement": agreement,
            "proofs": proofs, "overlap": overlap, "key_timeout_s": eval_parameter(args.run_dir, name, "key_timeout")}


def count_trace_elements(trace_file):
    """Number of trace elements in a text trace (<entry>.trace.txt), or None if the trace is missing. Every element
    starts with an upper-case letter (calls, catches and array dimensions are followed by a lower-case hex number);
    a switch case counts as its 6 I/O elements. The end marker E is not counted."""
    if not os.path.isfile(trace_file):
        return None
    with open(trace_file, encoding="utf-8") as f:
        text = f.read()
    return sum(c.isupper() for c in text) - text.count("E")


def load_proofs(run_dir, name, valid, seeds):
    """(trace elements, auto-mode seconds or None, status) of every KeY proof of the key runs."""
    proofs = []
    for m in [BASELINE] + valid:
        for s in seeds:
            key_dir = os.path.join(run_dir, name, "runs", m, "key-s%s" % s)
            rows = read_semicolon_csv(os.path.join(key_dir, "key-results.csv"))
            for r in rows or []:
                if r["status"] in ("not_reached", "prepare_error", "key_error"):
                    continue
                elements = count_trace_elements(os.path.join(key_dir, "traces", r["entry"] + ".trace.txt"))
                if elements is None:
                    continue
                t = int(r["automode_ms"]) / 1000.0 if r["automode_ms"] else None
                proofs.append((elements, t, r["status"]))
    return proofs


# ---- overlap of the traces of a key run (prefix tree) ----

TRACE_ELEMENT = re.compile(r"[A-Z][0-9a-f]*")


def read_trace_elements(trace_file):
    """The trace elements of a text trace as a tuple (an upper-case letter, followed by a lower-case hex number for
    calls, catches and array dimensions), without the end marker E; None if the trace is missing."""
    if not os.path.isfile(trace_file):
        return None
    with open(trace_file, encoding="utf-8") as f:
        return tuple(e for e in TRACE_ELEMENT.findall(f.read()) if e != "E")


def common_prefix(a, b):
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def prefix_tree_stats(traces):
    """Per distinct trace in lexicographic order: (its length, the common prefix with its predecessor, the longest
    prefix it shares with any other trace). In this order, every trace adds its elements beyond the common prefix
    with its predecessor to the prefix tree, and the longest common prefix with any other trace is the one with a
    neighbor."""
    ordered = sorted(traces)
    lcp = [0] + [common_prefix(a, b) for a, b in zip(ordered, ordered[1:])] + [0]
    return [(len(t), lcp[i], max(lcp[i], lcp[i + 1])) for i, t in enumerate(ordered)]


def overlap_elements(o):
    """(elements of all traces, of the distinct traces, nodes of the prefix tree) of a key run: the symbolic
    execution of one proof per trace, one proof per distinct trace and one shared proof over the prefix tree."""
    return (sum(o["lengths"]),
            sum(n for n, _, _ in o["tree"]),
            sum(n - lcp for n, lcp, _ in o["tree"]))


def load_overlap(run_dir, name, valid, seeds):
    """Per key run (unmutated program and every valid mutant, every seed) with at least one trace: the elements
    summed over the traces of all corpus entries that reach the method under test (one proof per trace) and over the
    distinct ones (one proof per distinct trace), and the prefix tree statistics of the distinct traces."""
    overlap = []
    for m in [BASELINE] + valid:
        for s in seeds:
            key_dir = os.path.join(run_dir, name, "runs", m, "key-s%s" % s)
            rows = read_semicolon_csv(os.path.join(key_dir, "key-results.csv"))
            traces = []
            for r in rows or []:
                if r["status"] == "not_reached":
                    continue
                t = read_trace_elements(os.path.join(key_dir, "traces", r["entry"] + ".trace.txt"))
                if t:
                    traces.append(t)
            if not traces:
                continue
            distinct = set(traces)
            overlap.append({"mutant": m, "seed": s, "traces": len(traces), "distinct": len(distinct),
                            "lengths": [len(t) for t in traces], "tree": prefix_tree_stats(distinct)})
    return overlap


def print_overlap(overlap):
    runs = [o for o in overlap if o["mutant"] != BASELINE]
    if not runs:
        return
    shared = [sh / n for o in runs for n, _, sh in o["tree"]]
    print("-" * 78)
    print("Trace overlap of the key runs on the mutants (prefix tree of the traces of a run):")
    print("  %d runs, %d traces (%d distinct); shared proof over the prefix tree relative to separate proofs:" % (
        len(runs), sum(o["traces"] for o in runs), sum(o["distinct"] for o in runs)))
    counts = [overlap_elements(o) for o in runs]
    tree = sum(c[2] for c in counts)
    for label, i in (("all traces", 0), ("distinct traces", 1)):
        ratios = [c[2] / c[i] for c in counts]
        print("  elements / %-15s: %5.1f%% of %d (pooled), per run median %.1f%%, min %.1f%%, max %.1f%%" % (
            label, 100.0 * tree / sum(c[i] for c in counts), sum(c[i] for c in counts), 100 * median(ratios),
            100 * min(ratios), 100 * max(ratios)))
    print("  prefix shared with another distinct trace of the run: median %.1f%% of a trace" % (100 * median(shared)))


def eval_parameter(run_dir, name, parameter):
    """Value of a run_mutation_eval.py parameter the results were produced with (from eval_config.json)."""
    path = os.path.join(run_dir, name, "eval_config.json")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)["parameters"].get(parameter)


def print_beyond_fuzzer(runs, valid, seeds):
    """Mutants on which KeY found a bug although Jazzer, fuzzing the mutant with the asserts checked on every
    execution, found nothing."""
    rows = [runs[("key", s)][m] for s in seeds for m in valid if m in runs.get(("key", s), {})]
    if not rows:
        return
    beyond = sorted({r["mutant"] for r in rows if not r["corpus_finding"] and int(r["key_open"] or 0) > 0}, key=int)
    fuzzer = {r["mutant"] for r in rows if r["corpus_finding"]}
    print("-" * 78)
    print("Jazzer found a bug in %d of %d mutants while fuzzing them for the corpus;" % (
        len(fuzzer), len({r["mutant"] for r in rows})))
    print("  KeY found a bug in %d mutant(s) where Jazzer found none: %s" % (len(beyond), beyond))


def mutant_traces(runs, valid, seeds, approaches):
    """Per mutant, means over the seeds with a key run: traces (corpus entries reaching the method under test),
    traces with an open proof / a KeY timeout, corpus entries that fail on the concrete run (see concrete_oracle),
    and the inputs needed to find the bug."""
    if "key" not in approaches:
        return []
    concrete = concrete_oracle(approaches)
    per_mutant = []
    for m in valid:
        key_rows = [runs[("key", s)][m] for s in seeds if m in runs.get(("key", s), {})]
        if not key_rows:
            continue
        concrete_rows = [runs[(concrete, s)][m] for s in seeds if m in runs.get((concrete, s), {})]

        def avg(rows, field):
            return mean([int(r[field] or 0) for r in rows])

        def first(rows, field):
            return mean([int(r[field]) for r in rows if r.get(field)])

        per_mutant.append({
            "mutant": m,
            "concrete_oracle": concrete,
            "corpus": avg(key_rows, "corpus_size"),
            "traces": avg(key_rows, "key_proofs"),
            "key_bug": avg(key_rows, "key_open"),
            "key_timeout": avg(key_rows, "key_timeout"),
            "concrete_fail": avg(concrete_rows, "run_failures"),
            # inputs (in replay order) up to the first KeY bug / concrete failure, mean over seeds that found one
            "inputs_to_key": first(key_rows, "inputs_to_key"),
            "inputs_to_fail": mean([int(r["inputs_to_kill"]) for r in concrete_rows
                                    if r["status"] == "killed" and r["reason"] != "key" and r["inputs_to_kill"]]),
        })
    return per_mutant


# ---- agreement of KeY and the concrete run per corpus entry ----

# cells of the per-entry comparison, in stacking order
AGREEMENT_CELLS = ["both", "key_only", "run_only", "neither", "no_result"]
AGREEMENT_LABELS = {
    "both": "Both find the bug",
    "key_only": "Only KeY (input passes)",
    "run_only": "Only concrete run (KeY closed)",
    "neither": "Both OK",
    "no_result": "No KeY result (timeout, step limit, error)",
}
# extra counts that are not traces of the method under test
AGREEMENT_EXTRA = ["not_reached", "not_reached_fail", "unmatched"]
KEY_NO_RESULT = ("step_limit", "key_timeout", "key_error", "prepare_error")


def read_semicolon_csv(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter=";"))


def compare_entries(key_rows, replay_rows):
    """Joins key-results.csv and replay-results.csv of the same corpus by entry name; returns the counts per cell."""
    counts = dict.fromkeys(AGREEMENT_CELLS + AGREEMENT_EXTRA, 0)
    key = {r["entry"]: r["status"] for r in key_rows}
    concrete = {r["entry"]: r["status"] != "ok" for r in replay_rows}
    # the replay stops at the first timeout, so an entry may be missing in one of the runs
    counts["unmatched"] = len(key.keys() ^ concrete.keys())
    for entry in key.keys() & concrete.keys():
        status, fails = key[entry], concrete[entry]
        if status == "not_reached":
            counts["not_reached"] += 1
            counts["not_reached_fail"] += fails
        elif status in KEY_NO_RESULT:
            counts["no_result"] += 1
        elif status == "open":
            counts["both" if fails else "key_only"] += 1
        else:
            counts["run_only" if fails else "neither"] += 1
    return counts


def load_agreement(run_dir, name, valid, seeds, approaches):
    """Agreement counts per (mutant, seed) for the unmutated program and every valid mutant with a key run.
    The concrete verdict comes from the replay of the concrete_oracle approach on the same corpus."""
    if "key" not in approaches:
        return None
    concrete = concrete_oracle(approaches)
    rows = []
    for m in [BASELINE] + valid:
        for s in seeds:
            mutant_dir = os.path.join(run_dir, name, "runs", m)
            key_rows = read_semicolon_csv(os.path.join(mutant_dir, "key-s%s" % s, "key-results.csv"))
            replay_rows = read_semicolon_csv(os.path.join(mutant_dir, "%s-s%s" % (concrete, s), "replay-results.csv"))
            if key_rows is None or replay_rows is None:
                continue
            rows.append(dict(compare_entries(key_rows, replay_rows), mutant=m, seed=s))
    return {"concrete": concrete, "rows": rows}


def sum_cells(rows):
    return {c: sum(r[c] for r in rows) for c in AGREEMENT_CELLS + AGREEMENT_EXTRA}


def print_agreement(agreement):
    if not agreement or not agreement["rows"]:
        return
    print("-" * 78)
    print("Agreement of KeY and the concrete run (%s) per corpus entry, summed over seeds:" % (
        CONCRETE_LABELS[agreement["concrete"]]))
    for label, rows in (("unmutated", [r for r in agreement["rows"] if r["mutant"] == BASELINE]),
                        ("mutants", [r for r in agreement["rows"] if r["mutant"] != BASELINE])):
        if not rows:
            continue
        c = sum_cells(rows)
        traces = sum(c[k] for k in AGREEMENT_CELLS)
        print("  %-9s %6d traces: %s" % (label, traces, ", ".join(
            "%s %d" % (k.replace("_", " "), c[k]) for k in AGREEMENT_CELLS)))
        print("  %-9s %6s not reaching the method: %d (%d failing), unmatched entries: %d" % (
            "", "", c["not_reached"], c["not_reached_fail"], c["unmatched"]))
        flagged = c["both"] + c["key_only"]
        if flagged:
            print("  %-9s %6s KeY only / all KeY-flagged traces: %.1f%%" % ("", "", 100.0 * c["key_only"] / flagged))
    mutants = defaultdict(list)
    for r in agreement["rows"]:
        if r["mutant"] != BASELINE:
            mutants[r["mutant"]].append(r)
    only_key = sorted((m for m, rs in mutants.items()
                       if sum(r["key_only"] for r in rs) and not sum(r["both"] + r["run_only"] for r in rs)), key=int)
    inconsistent = sorted((m for m, rs in mutants.items() if sum(r["run_only"] for r in rs)), key=int)
    print("  mutants flagged by KeY on no failing input: %d %s" % (len(only_key), only_key))
    print("  mutants with inconsistent entries (KeY closed, run fails): %d %s" % (len(inconsistent), inconsistent))


def print_mutant_traces(per_mutant):
    if not per_mutant:
        return
    fmt = lambda v: "-" if v is None else "%.1f" % v if v % 1 else "%d" % v
    concrete = per_mutant[0]["concrete_oracle"]
    print("-" * 78)
    print("Per mutant (mean over seeds; concrete run: %s):" % CONCRETE_LABELS[concrete])
    print("%6s %7s %7s %8s %8s %9s %9s %9s" % (
        "mutant", "corpus", "traces", "KeY bug", "KeY t/o", "run fail", "in->KeY", "in->fail"))
    for p in per_mutant:
        print("%6s %7s %7s %8s %8s %9s %9s %9s" % (
            p["mutant"], fmt(p["corpus"]), fmt(p["traces"]), fmt(p["key_bug"]), fmt(p["key_timeout"]),
            fmt(p["concrete_fail"]), fmt(p["inputs_to_key"]), fmt(p["inputs_to_fail"])))
    print("  traces = corpus entries reaching the method under test; run fail = corpus entries failing on the\n"
          "  concrete run; in->KeY / in->fail = corpus inputs up to the first KeY bug / concrete failure")


def print_summary(name, valid, mutants, seeds, approaches, summary, kill_sets, args):
    counts = defaultdict(int)
    for m in mutants.values():
        counts[m["status"]] += 1
    print("=" * 78)
    print("%s: %d valid mutants (%s), seeds: %s" % (
        name, len(valid), ", ".join("%s %d" % (k, v) for k, v in sorted(counts.items())), ", ".join(seeds)))
    print("-" * 78)
    print("%-28s %8s %12s %8s %6s %6s %8s" % ("approach", "score", "killed(any)", "wall", "error", "todo", "KeY lim"))
    for a in approaches:
        s = summary[a]
        score = "%5.1f%%" % (100 * s["score"])
        if len(seeds) > 1:
            score += " (%.0f-%.0f)" % (100 * s["score_min"], 100 * s["score_max"])
        key_lim = "%d/%d" % (s["step_limit_runs"], s["key_timeout_runs"]) if a == "key" else ""
        print("%-28s %8s %12s %8d %6d %6d %8s" % (
            APPROACH_LABELS[a], score, "%d/%d" % (s["killed_any"], len(valid)),
            s["wall_limit"], s["error"], s["missing"], key_lim))
    if "key" in approaches:
        print("  KeY lim = runs with a proof stopped at the rule-application limit / runs with a KeY timeout")
        s = summary["key"]
        per_seed = " (mean over seeds)" if len(seeds) > 1 else ""
        print("KeY: traces with a bug: %.1f, traces with a KeY timeout: %.1f%s" % (
            s["key_open_traces"], s["key_timeout_traces"], per_seed))
        t = s["key_times"]
        if t:
            print("KeY: time to first bug in %d run(s): median %.1fs, mean %.1fs, min %.1fs, max %.1fs" % (
                len(t), median(t), mean(t), t[0], t[-1]))
        else:
            print("KeY: no bug found in any run")

    print("-" * 78)
    all_valid = set(valid)
    k, r, c = kill_sets.get("key", set()), kill_sets.get("runtime", set()), kill_sets.get("crash", set())
    if "key" in approaches and "runtime" in approaches:
        print("Kill-set overlap (killed in any seed):")
        print("  key and runtime : %d" % len(k & r))
        print("  key only        : %d %s" % (len(k - r), sorted(k - r, key=int)))
        print("  runtime only    : %d %s" % (len(r - k), sorted(r - k, key=int)))
        print("  neither         : %d" % len(all_valid - k - r))
    if "crash" in approaches:
        extra = {a: kill_sets[a] - c for a in approaches if a != "crash"}
        for a, ids in extra.items():
            print("  %-16s: %d mutants beyond crash-only fuzzing" % (a, len(ids)))
        print("  not killed by any approach (possibly equivalent): %d %s" % (
            len(all_valid - set().union(*kill_sets.values())),
            sorted(all_valid - set().union(*kill_sets.values()), key=int)))

    if args.survivors:
        print("-" * 78)
        for a in approaches:
            survivors = sorted(all_valid - kill_sets[a], key=int)
            print("Survivors of %s (%d):" % (APPROACH_LABELS[a], len(survivors)))
            for m in survivors:
                info = mutants[m]
                print("  %4s %-4s line %-4s %s" % (m, info["operator"], info["line"], info["transformation"]))


# ---- plots ----

def style_axes(ax):
    ax.set_facecolor(SURFACE)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(AXIS)
    ax.tick_params(colors=TEXT_SECONDARY, length=0)
    ax.yaxis.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def plot_scores(results, run_dir):
    import matplotlib.pyplot as plt
    import numpy as np

    approaches = [a for a in APPROACHES if any(a in r["approaches"] for r in results)]
    names = [r["name"] for r in results]
    x = np.arange(len(names))
    width = 0.8 / len(approaches)

    fig, ax = plt.subplots(figsize=(max(6.0, 2.2 * len(names) + 2), 4.5), facecolor=SURFACE)
    style_axes(ax)
    for i, a in enumerate(approaches):
        values = [100 * r["summary"][a]["score"] if a in r["summary"] else 0 for r in results]
        # 2px surface gap between adjacent bars
        bars = ax.bar(x + (i - (len(approaches) - 1) / 2) * width, values, width,
                      color=COLORS[a], edgecolor=SURFACE, linewidth=2, label=APPROACH_LABELS[a])
        for bar, v in zip(bars, values):
            ax.annotate("%.0f%%" % v, (bar.get_x() + bar.get_width() / 2, v), xytext=(0, 3),
                        textcoords="offset points", ha="center", va="bottom", fontsize=8, color=TEXT_SECONDARY)

    ax.set_xticks(x)
    ax.set_xticklabels(["%s\n(%d mutants)" % (r["name"], r["valid"]) for r in results], color=TEXT_PRIMARY)
    ax.set_ylim(0, 105)
    ax.set_ylabel("Mutations flagged as buggy (%)", color=TEXT_SECONDARY)
    #ax.set_title("Mutants flagged as buggy", color=TEXT_PRIMARY, loc="left")
    ax.legend(frameon=False, loc="upper left", bbox_to_anchor=(0, -0.15), ncol=len(approaches),
              labelcolor=TEXT_SECONDARY)
    fig.tight_layout()
    out = os.path.join(run_dir, "mutation_scores.png")
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


AGREEMENT_COLORS = {"both": "#2a78d6", "key_only": COLORS["key"], "run_only": COLORS["runtime"], "neither": AXIS}


def draw_agreement_bars(ax, positions, shares, horizontal, height):
    """100% stacked bars, one per position; shares: list of {cell: share}. 'No KeY result' is dotted."""
    bar = ax.barh if horizontal else ax.bar
    base = [0.0] * len(positions)
    for cell in AGREEMENT_CELLS:
        values = [100 * sh[cell] for sh in shares]
        style = {"facecolor": SURFACE, "edgecolor": TEXT_SECONDARY, "hatch": "...", "linewidth": 0.8} \
            if cell == "no_result" else {"color": AGREEMENT_COLORS[cell], "edgecolor": SURFACE, "linewidth": 1.5}
        offset = {"left": base} if horizontal else {"bottom": base}
        bar(positions, values, height, label=AGREEMENT_LABELS[cell], **offset, **style)
        base = [b + v for b, v in zip(base, values)]


def shares_of(counts):
    traces = sum(counts[c] for c in AGREEMENT_CELLS)
    return {c: counts[c] / traces if traces else 0.0 for c in AGREEMENT_CELLS}, traces


def plot_agreement(results, run_dir):
    """Per benchmark: share of traces per agreement cell, for the unmutated program and the mutants (pooled)."""
    import matplotlib.pyplot as plt

    bars = []  # (label, shares, traces)
    for r in results:
        if not r["agreement"]:
            continue
        for label, rows in (
                            ("%s (unmutated)" % r["name"], [x for x in r["agreement"]["rows"] if x["mutant"] == BASELINE]),
                            ("%s (%d mutants)" % (r["name"], r["valid"]),
                             [x for x in r["agreement"]["rows"] if x["mutant"] != BASELINE])):
            sh, traces = shares_of(sum_cells(rows))
            if traces:
                bars.append((label, sh, traces))
    if not bars:
        return
    concrete = next(r["agreement"]["concrete"] for r in results if r["agreement"])

    fig, ax = plt.subplots(figsize=(8, 1.0 + 0.55 * len(bars) + 1.2), facecolor=SURFACE)
    style_axes(ax)
    ax.yaxis.grid(False)
    ax.xaxis.grid(True, color=GRID, linewidth=0.8)
    ax.spines["bottom"].set_visible(False)
    y = list(range(len(bars)))[::-1]
    draw_agreement_bars(ax, y, [b[1] for b in bars], horizontal=True, height=0.6)
    for yi, (_, sh, traces) in zip(y, bars):
        ax.annotate("n=%d" % traces, (100, yi), xytext=(4, 0), textcoords="offset points", va="center",
                    fontsize=8, color=TEXT_SECONDARY)
        # the cell the argument rests on gets a direct label
        if sh["key_only"] >= 0.04:
            x = 100 * sh["both"] + 50 * sh["key_only"]
            ax.annotate("%.0f%%" % (100 * sh["key_only"]), (x, yi), ha="center", va="center", fontsize=8,
                        color=TEXT_PRIMARY)
    ax.set_yticks(y)
    ax.set_yticklabels([b[0] for b in bars], color=TEXT_PRIMARY)
    ax.set_xlim(0, 100)
    ax.set_xlabel("Share of traces (%)", color=TEXT_SECONDARY)
    ax.set_title("KeY vs. concrete run (%s) on the same inputs" % CONCRETE_LABELS[concrete],
                 color=TEXT_PRIMARY, loc="left")
    ax.legend(frameon=False, loc="upper left", bbox_to_anchor=(0, -0.12), ncol=3, labelcolor=TEXT_SECONDARY,
              fontsize=8)
    fig.tight_layout()
    out = os.path.join(run_dir, "agreement.png")
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


def plot_agreement_mutants(results, run_dir):
    """Per mutant (summed over seeds): share of traces per agreement cell, sorted by the share of 'KeY only'."""
    import matplotlib.pyplot as plt
    import numpy as np

    panels = []
    for r in results:
        if not r["agreement"]:
            continue
        by_mutant = defaultdict(list)
        for x in r["agreement"]["rows"]:
            if x["mutant"] != BASELINE:
                by_mutant[x["mutant"]].append(x)
        bars = []
        for m, rows in by_mutant.items():
            sh, traces = shares_of(sum_cells(rows))
            if traces:
                bars.append((m, sh, traces))
        bars.sort(key=lambda b: (-b[1]["key_only"], -b[1]["both"], int(b[0])))
        if bars:
            panels.append((r["name"], bars))
    if not panels:
        return

    widest = max(len(b) for _, b in panels)
    fig, axes = plt.subplots(len(panels), 1, figsize=(max(7.0, 0.32 * widest + 2), 3.4 * len(panels)),
                             facecolor=SURFACE, squeeze=False)
    for ax, (name, bars) in zip(axes[:, 0], panels):
        style_axes(ax)
        x = np.arange(len(bars))
        draw_agreement_bars(ax, x, [b[1] for b in bars], horizontal=False, height=0.8)
        ax.set_xticks(x)
        ax.set_xticklabels([b[0] for b in bars], color=TEXT_PRIMARY, fontsize=7)
        ax.set_xlim(-0.6, len(bars) - 0.4)
        ax.set_ylim(0, 100)
        ax.set_xlabel("Mutant (sorted by share of \"only KeY\")", color=TEXT_SECONDARY)
        ax.set_ylabel("Share of traces (%)", color=TEXT_SECONDARY)
        ax.set_title(name, color=TEXT_PRIMARY, loc="left")
    axes[-1, 0].legend(frameon=False, loc="upper left", bbox_to_anchor=(0, -0.25), ncol=3,
                       labelcolor=TEXT_SECONDARY, fontsize=8)
    fig.tight_layout()
    out = os.path.join(run_dir, "agreement_mutants.png")
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


PROOF_STYLES = {
    "closed": {"label": "Closed", "color": COLORS["crash"]},
    "open": {"label": "Open goals (bug)", "color": COLORS["key"]},
    "step_limit": {"label": "Rule-application limit", "color": COLORS["runtime"]},
}


def plot_key_time(results, run_dir):
    """KeY auto-mode time over the trace length (trace elements), one log-log panel per benchmark. Timeouts have no
    auto-mode time; they are drawn as hollow markers on a dashed line at --key-timeout."""
    import matplotlib.pyplot as plt

    results = [r for r in results if r["proofs"]]
    if not results:
        return
    fig, axes = plt.subplots(1, len(results), figsize=(max(6.0, 4.2 * len(results)), 4.4), facecolor=SURFACE,
                             squeeze=False, sharey=True)
    for ax, r in zip(axes[0], results):
        style_axes(ax)
        ax.xaxis.grid(True, color=GRID, linewidth=0.8)
        for status, style in PROOF_STYLES.items():
            pts = [(n, t) for n, t, st in r["proofs"] if st == status and t is not None]
            if pts:
                # 8px markers with a surface ring, so overlapping points stay separable
                ax.scatter([p[0] for p in pts], [max(p[1], 1e-3) for p in pts], s=22, color=style["color"],
                           edgecolors=SURFACE, linewidths=0.8, alpha=0.85, label=style["label"], zorder=3)
        timeouts = [n for n, _, st in r["proofs"] if st == "key_timeout"]
        if timeouts and r["key_timeout_s"]:
            ax.axhline(r["key_timeout_s"], color=TEXT_SECONDARY, linewidth=0.8, linestyle="--", zorder=2)
            ax.scatter(timeouts, [r["key_timeout_s"]] * len(timeouts), s=26, facecolors="none",
                       edgecolors=TEXT_SECONDARY, linewidths=1.0, label="KeY timeout (at --key-timeout)", zorder=3)
            ax.annotate("--key-timeout %ds" % r["key_timeout_s"], (1, r["key_timeout_s"]),
                        xycoords=("axes fraction", "data"), xytext=(0, 3), textcoords="offset points",
                        ha="right", va="bottom", fontsize=8, color=TEXT_SECONDARY)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("Trace elements", color=TEXT_SECONDARY)
        ax.set_title("%s (%d proofs)" % (r["name"], len(r["proofs"])), color=TEXT_PRIMARY, loc="left")
    axes[0, 0].set_ylabel("KeY auto-mode time (s)", color=TEXT_SECONDARY)
    handles, labels = {}, {}
    for ax in axes[0]:
        for h, l in zip(*ax.get_legend_handles_labels()):
            handles.setdefault(l, h)
    fig.legend(list(handles.values()), list(handles.keys()), frameon=False, loc="lower left", ncol=4,
               labelcolor=TEXT_SECONDARY, fontsize=8)
    fig.tight_layout(rect=(0, 0.08, 1, 1))
    out = os.path.join(run_dir, "key_time.png")
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


# the two baselines a shared proof over the prefix tree is compared with (index into overlap_elements)
OVERLAP_VARIANTS = [
    (0, "vs. one proof per trace", COLORS["key"]),
    (1, "vs. one proof per distinct trace", COLORS["crash"]),
]


def plot_trace_overlap(results, run_dir):
    """Per benchmark (key runs on the mutants): left, one dot per run with the elements of the prefix tree of its
    traces relative to the elements of all its traces and of its distinct traces (the share of the symbolic execution
    a shared proof still has to do; the gap between the two is what skipping duplicate traces alone saves); right,
    one dot per distinct trace with the share of its prefix that it shares with another distinct trace of the run."""
    import matplotlib.pyplot as plt
    import numpy as np

    rows = []  # (label, runs)
    for r in results:
        runs = [o for o in r["overlap"] if o["mutant"] != BASELINE]
        if runs:
            rows.append(("%s\n(%d runs, %d traces,\n%d distinct)" % (
                r["name"], len(runs), sum(o["traces"] for o in runs), sum(o["distinct"] for o in runs)), runs))
    if not rows:
        return

    rng = np.random.default_rng(0)
    fig, axes = plt.subplots(1, 2, figsize=(12, 1.6 + 1.3 * len(rows)), facecolor=SURFACE, sharey=True)
    y = np.arange(len(rows))[::-1]
    for ax in axes:
        style_axes(ax)
        ax.yaxis.grid(False)
        ax.xaxis.grid(True, color=GRID, linewidth=0.8)
        ax.spines["bottom"].set_visible(False)
        ax.set_xlim(-2, 102)

    def strip(ax, yi, values, color, label=None, median_above=True):
        """Jittered dots with a median tick, labeled above or below the strip."""
        ax.scatter(values, yi + rng.uniform(-0.12, 0.12, len(values)), s=22, color=color, edgecolors=SURFACE,
                   linewidths=0.8, alpha=0.8, zorder=3, label=label)
        med = median(values)
        ax.plot([med, med], [yi - 0.17, yi + 0.17], color=TEXT_PRIMARY, linewidth=2, zorder=4)
        ax.annotate("median %.0f%%" % med, (med, yi + (0.17 if median_above else -0.17)),
                    xytext=(0, 2 if median_above else -2), textcoords="offset points", ha="center",
                    va="bottom" if median_above else "top", fontsize=8, color=TEXT_PRIMARY)

    for yi, (_, runs) in zip(y, rows):
        counts = [overlap_elements(o) for o in runs]
        pooled = []
        for j, (i, label, color) in enumerate(OVERLAP_VARIANTS):
            strip(axes[0], yi + (0.2 if j == 0 else -0.2), [100.0 * c[2] / c[i] for c in counts], color,
                  label=label if yi == y[0] else None, median_above=j == 0)
            pooled.append("%.0f%%" % (100.0 * sum(c[2] for c in counts) / sum(c[i] for c in counts)))
        axes[0].annotate("all runs:\n%s" % "\n".join(pooled), (1, yi), xycoords=("axes fraction", "data"),
                         xytext=(-2, 0), textcoords="offset points", ha="right", va="center", fontsize=8,
                         color=TEXT_SECONDARY)
        strip(axes[1], yi, [100.0 * sh / n for o in runs for n, _, sh in o["tree"]], COLORS["key"])

    axes[0].set_xlabel("Elements in the prefix tree (% of the elements of separate proofs)", color=TEXT_SECONDARY)
    axes[0].set_title("Work of one shared proof per mutant", color=TEXT_PRIMARY, loc="left")
    axes[1].set_xlabel("Prefix shared with another distinct trace (% of the trace)", color=TEXT_SECONDARY)
    axes[1].set_title("Overlap per distinct trace", color=TEXT_PRIMARY, loc="left")
    axes[0].set_yticks(y)
    axes[0].set_yticklabels([row[0] for row in rows], color=TEXT_PRIMARY)
    axes[0].set_ylim(-0.6, len(rows) - 0.4)
    fig.legend(*axes[0].get_legend_handles_labels(), frameon=False, loc="lower left", ncol=2,
               labelcolor=TEXT_SECONDARY, fontsize=8)
    fig.text(0.99, 0.015, "Left: one dot per key run (mutant, seed), lower = more work saved. "
                          "Right: one dot per distinct trace, higher = later divergence.",
             ha="right", fontsize=8, color=TEXT_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    out = os.path.join(run_dir, "trace_overlap.png")
    fig.savefig(out, dpi=150, facecolor=SURFACE)
    print("Saved " + out)


def write_overlap_csv(results, run_dir):
    rows = [(r["name"], o) for r in results for o in r["overlap"]]
    if not rows:
        return
    out = os.path.join(run_dir, "trace_overlap.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["program", "mutant", "seed", "traces", "distinct_traces", "elements", "elements_distinct",
                         "prefix_tree_nodes", "prefix_tree_share", "prefix_tree_share_distinct", "max_trace_len",
                         "median_shared_prefix_share_distinct"])
        for name, o in rows:
            elements, elements_distinct, nodes = overlap_elements(o)
            writer.writerow([name, o["mutant"], o["seed"], o["traces"], o["distinct"], elements, elements_distinct,
                             nodes, "%.4f" % (nodes / elements), "%.4f" % (nodes / elements_distinct),
                             max(o["lengths"]),
                             "%.4f" % median(sh / n for n, _, sh in o["tree"])])
    print("Saved " + out)


def write_agreement_csv(results, run_dir):
    rows = [(r["name"], r["agreement"]["concrete"], x) for r in results if r["agreement"] for x in r["agreement"]["rows"]]
    if not rows:
        return
    out = os.path.join(run_dir, "agreement.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["program", "mutant", "seed", "concrete_oracle"] + AGREEMENT_CELLS + AGREEMENT_EXTRA)
        for name, concrete, x in rows:
            writer.writerow([name, x["mutant"], x["seed"], concrete] + [x[c] for c in AGREEMENT_CELLS + AGREEMENT_EXTRA])
    print("Saved " + out)


def write_per_mutant_csv(results, run_dir):
    rows = [(r["name"], p) for r in results for p in r["per_mutant"]]
    if not rows:
        return
    fields = ["mutant", "concrete_oracle", "corpus", "traces", "key_bug", "key_timeout", "concrete_fail",
              "inputs_to_key", "inputs_to_fail"]
    out = os.path.join(run_dir, "per_mutant.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["program"] + fields)
        for name, p in rows:
            writer.writerow([name] + ["" if p[k] is None else "%.2f" % p[k] if isinstance(p[k], float) else p[k]
                                      for k in fields])
    print("Saved " + out)


def write_summary_csv(results, run_dir):
    out = os.path.join(run_dir, "summary.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["program", "approach", "valid_mutants", "mutation_score", "score_min", "score_max",
                         "killed_any_seed", "wall_limit_runs", "error_runs", "missing_runs",
                         "key_step_limit_runs", "key_timeout_runs", "key_bug_traces", "key_timeout_traces",
                         "key_first_bug_runs", "key_first_bug_median_s", "key_first_bug_mean_s"])
        for r in results:
            for a in r["approaches"]:
                s = r["summary"][a]
                key = []
                if a == "key":
                    t = s["key_times"]
                    key = ["%.1f" % s["key_open_traces"], "%.1f" % s["key_timeout_traces"], len(t),
                           "%.1f" % median(t) if t else "", "%.1f" % mean(t) if t else ""]
                writer.writerow([r["name"], a, r["valid"], "%.4f" % s["score"], "%.4f" % s["score_min"],
                                 "%.4f" % s["score_max"], s["killed_any"], s["wall_limit"], s["error"],
                                 s["missing"], s["step_limit_runs"], s["key_timeout_runs"]] + key)
    print("Saved " + out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("benchmarks", nargs="*", help="Benchmark names (default: all with a results.csv)")
    parser.add_argument("--run", help="generate_mutants.py output directory in evaluation/mutation-out "
                                      "(e.g. mutation_2026-11-01_17.23.31) or a path (default: latest)")
    parser.add_argument("--survivors", action="store_true", help="List surviving mutants per approach")
    parser.add_argument("--wall-limit-killed", action="store_true",
                        help="Count runs that hit the wall-clock limit as killed (default: survived)")
    parser.add_argument("--no-plot", action="store_true", help="Skip plotting")
    args = parser.parse_args()

    args.run_dir = resolve_run_dir(args.run)
    names = args.benchmarks or sorted(
        d for d in os.listdir(args.run_dir) if os.path.isfile(os.path.join(args.run_dir, d, "results.csv")))
    if not names:
        sys.exit("No results found in " + args.run_dir)

    results = [analyze(name, args) for name in names]
    write_summary_csv(results, args.run_dir)
    write_per_mutant_csv(results, args.run_dir)
    write_agreement_csv(results, args.run_dir)
    write_overlap_csv(results, args.run_dir)

    if not args.no_plot:
        import matplotlib.pyplot as plt
        plot_scores(results, args.run_dir)
        plot_agreement(results, args.run_dir)
        plot_agreement_mutants(results, args.run_dir)
        plot_key_time(results, args.run_dir)
        plot_trace_overlap(results, args.run_dir)
        plt.show()


if __name__ == "__main__":
    main()
