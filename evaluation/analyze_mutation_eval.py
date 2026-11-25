"""
Analyze the results of run_mutation_eval.py (the latest run in evaluation/mutation-out, or --run).

Printed per benchmark, and saved to the run directory (one row per benchmark and ...):
  summary.csv        approach: mutation score, inputs flagged as buggy, KeY time and limits
  per_mutant.csv     mutant (mean over seeds): corpus size, traces, inputs flagged per approach, KeY time
  agreement.csv      key run: KeY vs. the concrete run (runtime asserts, else crashes) on the same corpus entries
  trace_overlap.csv  key run: how much its traces share (the work a multi-trace proof can save)
  proof_modes.csv    (mutant, seed): per-trace (key) vs. multi-trace (key-multi) verdict, time to verdict and effort
Plots: see plots.py.

Usage:
  python analyze_mutation_eval.py                  # all benchmarks with a results.csv
  python analyze_mutation_eval.py Max Quicksort --survivors --no-plot
"""

import argparse
import os
import sys
from dataclasses import dataclass, field
from statistics import median

from common import (APPROACH_LABELS, BASELINE, KEY_APPROACHES, by_id, mean, resolve_run_dir, write_records_csv)
from results import NO_KEY_RESULT, Benchmark


# ---- records ----

@dataclass
class ApproachSummary:
    program: str
    approach: str
    valid_mutants: int
    score: float  # killed / valid mutants, mean over seeds
    score_min: float
    score_max: float
    killed_any_seed: int
    wall_limit_runs: int
    error_runs: int
    missing_runs: int
    inputs: int  # corpus entries replayed, summed over all runs on mutants
    flagged_inputs: int | None  # of them flagged as buggy (None for key-multi: its proof cannot attribute a bug)
    key_proofs: int | None
    key_open: int | None
    key_step_limit: int | None
    key_timeout: int | None
    key_error: int | None
    key_finished_median_s: float | None  # per run: seconds until the last proof finished
    key_finished_max_s: float | None
    key_automode_s: float | None  # KeY auto-mode time summed over all proofs
    time_to_key_median_s: float | None  # per run with a bug: seconds until the first open proof
    key_bug_runs: int | None


@dataclass
class MutantStats:
    """Means over the seeds of a mutant."""
    program: str
    mutant: str
    corpus: float | None
    traces: float | None
    crash_flagged: float | None
    runtime_flagged: float | None
    key_flagged: float | None  # failing on the concrete run or open per-trace proof
    key_open: float | None
    key_timeout: float | None
    key_finished_s: float | None
    killed_by: str


AGREEMENT_CELLS = ["both", "key_only", "run_only", "neither", "no_result"]  # stacking order in plots
AGREEMENT_LABELS = {
    "both": "Both find the bug",
    "key_only": "Only KeY (input passes)",
    "run_only": "Only concrete run (KeY closed)",
    "neither": "Both OK",
    "no_result": "No KeY result (timeout, step limit, error)",
}
CONCRETE_LABELS = {"runtime": "runtime asserts", "key": "crashes"}


@dataclass
class Agreement:
    """KeY (key run) vs. the concrete run of the same corpus entries. run_only should never happen: KeY closed the
    proof but the input fails (contract and asserts disagree, overflow, or a tracing/retracing bug)."""
    program: str
    mutant: str
    seed: str
    concrete: str  # approach of the concrete run: runtime, or key (its own crash oracle) without runtime runs
    both: int = 0
    key_only: int = 0
    run_only: int = 0
    neither: int = 0
    no_result: int = 0
    not_reached: int = 0  # entries not reaching the method under test
    not_reached_fail: int = 0  # ... of them failing on the concrete run
    unmatched: int = 0  # entries in only one of the runs (the replay stops at the first timeout)

    @property
    def traces(self):
        return sum(getattr(self, c) for c in AGREEMENT_CELLS)

    def shares(self):
        return {c: getattr(self, c) / self.traces if self.traces else 0.0 for c in AGREEMENT_CELLS}

    @classmethod
    def total(cls, rows):
        t = cls(rows[0].program, "", "", rows[0].concrete)
        for c in AGREEMENT_CELLS + ["not_reached", "not_reached_fail", "unmatched"]:
            setattr(t, c, sum(getattr(r, c) for r in rows))
        return t


@dataclass
class TraceOverlap:
    """Traces of a key run: elements of all traces (one proof per trace), of the distinct traces and nodes of their
    prefix tree (one multi-trace proof)."""
    program: str
    mutant: str
    seed: str
    traces: int
    distinct_traces: int
    elements: int
    elements_distinct: int
    prefix_tree_nodes: int
    max_trace_len: int
    # per distinct trace: share of it that it shares as prefix with another distinct trace
    shared_prefix: list[float] = field(metadata={"csv": False})


@dataclass
class ProofModePair:
    """key (one proof per trace) vs. key-multi (one proof over all traces) on the same corpus."""
    program: str
    mutant: str
    seed: str
    traces: int
    per_bug: bool
    multi_bug: bool
    per_no_result: int  # per-trace proofs without a result (step limit, timeout, error)
    multi_status: str
    per_verdict_s: float  # Run.time_to_verdict_s
    multi_verdict_s: float
    per_automode_s: float
    multi_automode_s: float
    per_nodes: int
    multi_nodes: int


@dataclass
class Report:
    bench: Benchmark
    summaries: list[ApproachSummary]
    mutants: list[MutantStats]
    agreement: list[Agreement]
    overlap: list[TraceOverlap]
    proof_modes: list[ProofModePair]
    proofs: list[tuple[int, float | None, str]]  # every per-trace proof: (trace elements, auto-mode s, status)


# ---- analysis ----

def summarize(bench, approach):
    runs = bench.runs_of(approach)
    scores = bench.scores(approach) or [0.0]
    is_key, is_multi = approach in KEY_APPROACHES, approach == "key-multi"

    def key_value(value):
        return value if is_key else None

    finished = sorted(t for r in runs if (t := r.key_finished_s) is not None)
    to_key = [t for r in runs if (t := r.time_to_key_s) is not None]
    return ApproachSummary(
        program=bench.name, approach=approach, valid_mutants=len(bench.valid),
        score=mean(scores), score_min=min(scores), score_max=max(scores),
        killed_any_seed=len(bench.killed(approach)),
        wall_limit_runs=sum(r.status == "wall_limit" for r in runs),
        error_runs=sum(r.status == "error" for r in runs),
        missing_runs=len(bench.valid) * len(bench.seeds) - len(runs),
        inputs=sum(r.corpus_size for r in runs),
        flagged_inputs=None if is_multi else sum(len(r.flagged_entries) for r in runs),
        key_proofs=key_value(sum(len(r.proofs) for r in runs)),
        key_open=key_value(sum(r.count("open") for r in runs)),
        key_step_limit=key_value(sum(r.count("step_limit") for r in runs)),
        key_timeout=key_value(sum(r.count("key_timeout") for r in runs)),
        key_error=key_value(sum(r.count("key_error") for r in runs)),
        key_finished_median_s=median(finished) if finished else None,
        key_finished_max_s=finished[-1] if finished else None,
        key_automode_s=key_value(sum(r.key_automode_s for r in runs)),
        time_to_key_median_s=median(to_key) if to_key else None,
        key_bug_runs=key_value(len(to_key)),
    )


def mutant_stats(bench, mutant):
    def avg(approach, f):
        return mean(v for r in bench.runs_of(approach, [mutant]) if (v := f(r)) is not None)

    return MutantStats(
        program=bench.name, mutant=mutant,
        corpus=avg("crash", lambda r: r.corpus_size) or avg("key", lambda r: r.corpus_size),
        traces=avg("key", lambda r: len(r.traces)),
        crash_flagged=avg("crash", lambda r: len(r.flagged_entries)),
        runtime_flagged=avg("runtime", lambda r: len(r.flagged_entries)),
        key_flagged=avg("key", lambda r: len(r.flagged_entries)),
        key_open=avg("key", lambda r: r.count("open")),
        key_timeout=avg("key", lambda r: r.count("key_timeout")),
        key_finished_s=avg("key", lambda r: r.key_finished_s),
        killed_by=",".join(a for a in bench.approaches if mutant in bench.killed(a)),
    )


def agreement(bench):
    """Per key run on the baseline and every mutant, joined by corpus entry with the concrete run."""
    if "key" not in bench.approaches:
        return []
    concrete = "runtime" if "runtime" in bench.approaches else "key"
    rows = []
    for key_run in bench.runs_of("key", [BASELINE] + bench.valid):
        concrete_run = bench.run(key_run.mutant, concrete, key_run.seed)
        if concrete_run is None:
            continue
        a = Agreement(bench.name, key_run.mutant, key_run.seed, concrete)
        key = {k.entry: k.status for k in key_run.key}
        fails = {r.entry: r.failed for r in concrete_run.replay}
        a.unmatched = len(key.keys() ^ fails.keys())
        for entry in key.keys() & fails.keys():
            status, failed = key[entry], fails[entry]
            if status == "not_reached":
                a.not_reached += 1
                a.not_reached_fail += failed
            elif status in NO_KEY_RESULT or status == "prepare_error":
                a.no_result += 1
            elif status == "open":
                if failed:
                    a.both += 1
                else:
                    a.key_only += 1
            elif failed:
                a.run_only += 1
            else:
                a.neither += 1
        rows.append(a)
    return rows


def common_prefix(a, b):
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def trace_overlap(bench):
    """Per key run with traces. In lexicographic order, every distinct trace adds the elements beyond its common
    prefix with its predecessor to the prefix tree, and its longest prefix shared with any other trace is the one
    shared with a neighbor."""
    rows = []
    for run in bench.runs_of("key", [BASELINE] + bench.valid):
        traces = [t for e in run.traces if (t := run.trace(e))]
        if not traces:
            continue
        ordered = sorted(set(traces))
        lcp = [0] + [common_prefix(a, b) for a, b in zip(ordered, ordered[1:])] + [0]
        rows.append(TraceOverlap(
            program=bench.name, mutant=run.mutant, seed=run.seed, traces=len(traces), distinct_traces=len(ordered),
            elements=sum(map(len, traces)), elements_distinct=sum(map(len, ordered)),
            prefix_tree_nodes=sum(len(t) - lcp[i] for i, t in enumerate(ordered)),
            max_trace_len=max(map(len, traces)),
            shared_prefix=[max(lcp[i], lcp[i + 1]) / len(t) for i, t in enumerate(ordered)]))
    return rows


def proof_modes(bench):
    pairs = []
    for per in bench.runs_of("key"):
        multi = bench.run(per.mutant, "key-multi", per.seed)
        if multi is None or not per.proofs or not multi.proofs:
            continue
        pairs.append(ProofModePair(
            program=bench.name, mutant=per.mutant, seed=per.seed, traces=len(per.traces),
            per_bug=per.key_found_bug, multi_bug=multi.key_found_bug,
            per_no_result=sum(per.count(s) for s in NO_KEY_RESULT), multi_status=multi.proofs[0].status,
            per_verdict_s=per.time_to_verdict_s, multi_verdict_s=multi.time_to_verdict_s,
            per_automode_s=per.key_automode_s, multi_automode_s=multi.key_automode_s,
            per_nodes=per.key_nodes, multi_nodes=multi.key_nodes))
    return pairs


def proof_points(bench):
    points = []
    for run in bench.runs_of("key", [BASELINE] + bench.valid):
        for k in run.proofs:
            if k.status != "key_error" and (trace := run.trace(k.entry)) is not None:
                points.append((len(trace), k.automode_s, k.status))
    return points


def analyze(bench):
    return Report(bench=bench,
                  summaries=[summarize(bench, a) for a in bench.approaches],
                  mutants=[mutant_stats(bench, m) for m in bench.valid],
                  agreement=agreement(bench),
                  overlap=trace_overlap(bench),
                  proof_modes=proof_modes(bench),
                  proofs=proof_points(bench))


# ---- printing ----

def fmt(value, digits=1):
    if value is None:
        return "-"
    if isinstance(value, float):
        return "%d" % value if value.is_integer() else "%.*f" % (digits, value)
    return str(value)


def pct(part, whole):
    return "%.1f%%" % (100.0 * part / whole) if whole else "-"


def print_table(headers, rows):
    widths = [max(len(str(c)) for c in col) for col in zip(headers, *rows)]
    for row in [headers] + rows:
        print("  " + "  ".join(str(c).rjust(w) if i else str(c).ljust(w) for i, (c, w) in enumerate(zip(row, widths))))


def section(title):
    print("-" * 78)
    print(title)


def print_summary(report):
    bench = report.bench
    statuses = {}
    for m in bench.mutants.values():
        statuses[m["status"]] = statuses.get(m["status"], 0) + 1
    print("=" * 78)
    print("%s: %d valid mutants (%s), seeds: %s" % (bench.name, len(bench.valid), ", ".join(
        "%s %d" % kv for kv in sorted(statuses.items())), ", ".join(bench.seeds)))

    section("Mutation score and inputs flagged as buggy (all runs on mutants):")
    rows = []
    for s in report.summaries:
        score = "%.1f%%" % (100 * s.score)
        if len(bench.seeds) > 1:
            score += " (%.0f-%.0f)" % (100 * s.score_min, 100 * s.score_max)
        flagged = "-" if s.flagged_inputs is None else "%d/%d (%s)" % (
            s.flagged_inputs, s.inputs, pct(s.flagged_inputs, s.inputs))
        rows.append([APPROACH_LABELS[s.approach], score, "%d/%d" % (s.killed_any_seed, s.valid_mutants), flagged,
                     s.wall_limit_runs, s.error_runs, s.missing_runs])
    print_table(["approach", "score", "killed(any)", "flagged inputs", "wall", "error", "todo"], rows)

    keys = [s for s in report.summaries if s.approach in KEY_APPROACHES]
    if keys:
        section("KeY:")
        print_table(["approach", "proofs", "open", "step lim", "timeout", "error", "finished (median/max)",
                     "auto-mode", "to bug (median)"],
                    [[APPROACH_LABELS[s.approach], s.key_proofs, s.key_open, s.key_step_limit, s.key_timeout,
                      s.key_error, "%ss / %ss" % (fmt(s.key_finished_median_s), fmt(s.key_finished_max_s)),
                      "%ss" % fmt(s.key_automode_s), "%ss (%d runs)" % (fmt(s.time_to_key_median_s), s.key_bug_runs)]
                     for s in keys])
        print("  finished = seconds from the start of a run until its last proof finished")


def print_kill_sets(report, survivors):
    bench = report.bench
    valid = set(bench.valid)
    kills = {a: bench.killed(a) for a in bench.approaches}
    section("Kill sets (killed in any seed):")
    if "key" in kills and "runtime" in kills:
        k, r = kills["key"], kills["runtime"]
        print("  key and runtime : %d" % len(k & r))
        print("  key only        : %d %s" % (len(k - r), by_id(k - r)))
        print("  runtime only    : %d %s" % (len(r - k), by_id(r - k)))
    if "crash" in kills:
        for a in kills:
            if a != "crash":
                print("  %-16s: %d mutants beyond crash-only fuzzing" % (a, len(kills[a] - kills["crash"])))
    alive = valid - set().union(*kills.values())
    print("  not killed by any approach (possibly equivalent): %d %s" % (len(alive), by_id(alive)))

    key_runs = bench.runs_of("key")
    if key_runs:
        fuzzer = {r.mutant for r in key_runs if r.corpus_finding}
        beyond = {r.mutant for r in key_runs if not r.corpus_finding and r.key_found_bug}
        print("  Jazzer found a bug while building the corpus in %d of %d mutants; KeY found one in %d more: %s" % (
            len(fuzzer), len({r.mutant for r in key_runs}), len(beyond), by_id(beyond)))

    if survivors:
        for a in bench.approaches:
            ids = by_id(valid - kills[a])
            print("Survivors of %s (%d):" % (APPROACH_LABELS[a], len(ids)))
            for m in ids:
                info = bench.mutants[m]
                print("  %4s %-4s line %-4s %s" % (m, info["operator"], info["line"], info["transformation"]))


def print_mutants(report):
    if not report.mutants:
        return
    section("Per mutant (mean over seeds):")
    print_table(["mutant", "corpus", "traces", "crash flag", "runtime flag", "key flag", "KeY open", "KeY t/o",
                 "KeY done s", "killed by"],
                [[m.mutant, fmt(m.corpus), fmt(m.traces), fmt(m.crash_flagged), fmt(m.runtime_flagged),
                  fmt(m.key_flagged), fmt(m.key_open), fmt(m.key_timeout), fmt(m.key_finished_s), m.killed_by or "-"]
                 for m in report.mutants])
    print("  flag = corpus entries flagged as buggy; key flag also counts entries failing on the concrete run")


def print_agreement(report):
    if not report.agreement:
        return
    section("KeY vs. concrete run (%s) per corpus entry:" % CONCRETE_LABELS[report.agreement[0].concrete])
    for label, rows in (("unmutated", [a for a in report.agreement if a.mutant == BASELINE]),
                        ("mutants", [a for a in report.agreement if a.mutant != BASELINE])):
        if not rows:
            continue
        t = Agreement.total(rows)
        print("  %-9s %d traces: %s" % (label, t.traces, ", ".join(
            "%s %d" % (c.replace("_", " "), getattr(t, c)) for c in AGREEMENT_CELLS)))
        print("  %-9s not reaching the method: %d (%d failing), unmatched: %d%s" % (
            "", t.not_reached, t.not_reached_fail, t.unmatched,
            ", KeY only / KeY flagged: " + pct(t.key_only, t.both + t.key_only) if t.both + t.key_only else ""))
    per_mutant = {}
    for a in report.agreement:
        if a.mutant != BASELINE:
            per_mutant.setdefault(a.mutant, []).append(a)
    totals = {m: Agreement.total(rows) for m, rows in per_mutant.items()}
    only_key = by_id(m for m, t in totals.items() if t.key_only and not t.both + t.run_only)
    inconsistent = by_id(m for m, t in totals.items() if t.run_only)
    print("  mutants flagged by KeY on no failing input: %d %s" % (len(only_key), only_key))
    print("  mutants with inconsistent entries (KeY closed, run fails): %d %s" % (len(inconsistent), inconsistent))


def print_overlap(report):
    rows = [o for o in report.overlap if o.mutant != BASELINE]
    if not rows:
        return
    section("Trace overlap of the key runs on mutants (prefix tree nodes / trace elements):")
    nodes = sum(o.prefix_tree_nodes for o in rows)
    for label, attr in (("all traces", "elements"), ("distinct traces", "elements_distinct")):
        total = sum(getattr(o, attr) for o in rows)
        ratios = [o.prefix_tree_nodes / getattr(o, attr) for o in rows]
        print("  vs. %-15s: %s of %d elements (pooled), per run median %.1f%%" % (
            label, pct(nodes, total), total, 100 * median(ratios)))
    print("  prefix shared with another distinct trace: median %.1f%% of a trace" % (
        100 * median(s for o in rows for s in o.shared_prefix)))


def print_proof_modes(report):
    pairs = report.proof_modes
    if not pairs:
        return
    section("Per-trace (key) vs. multi-trace (key-multi), %d runs with proofs in both:" % len(pairs))

    def mutants_where(f):
        return by_id({p.mutant for p in pairs if f(p)})

    print("  KeY finds a bug: both %d, per-trace only %s, multi-trace only %s, neither %d" % (
        sum(p.per_bug and p.multi_bug for p in pairs), mutants_where(lambda p: p.per_bug and not p.multi_bug),
        mutants_where(lambda p: p.multi_bug and not p.per_bug), sum(not p.per_bug and not p.multi_bug for p in pairs)))
    print("  time to verdict (first open proof, else last proof finished):")
    for label, group in (("both find a bug", [p for p in pairs if p.per_bug and p.multi_bug]),
                         ("both find none", [p for p in pairs if not p.per_bug and not p.multi_bug])):
        if group:
            ratios = [p.multi_verdict_s / p.per_verdict_s for p in group if p.per_verdict_s > 0]
            print("    %-16s %3d runs: median per-trace %.1fs, multi-trace %.1fs, multi/per median %s" % (
                label, len(group), median(p.per_verdict_s for p in group), median(p.multi_verdict_s for p in group),
                "%.2f" % median(ratios) if ratios else "-"))
    for label, attr in (("auto-mode s", "automode_s"), ("proof nodes", "nodes")):
        per, multi = sum(getattr(p, "per_" + attr) for p in pairs), sum(getattr(p, "multi_" + attr) for p in pairs)
        print("  %-12s: per-trace %s, multi-trace %s (%s)" % (label, fmt(float(per)), fmt(float(multi)),
                                                             pct(multi, per)))


def print_report(report, survivors):
    print_summary(report)
    print_kill_sets(report, survivors)
    print_mutants(report)
    print_agreement(report)
    print_overlap(report)
    print_proof_modes(report)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("benchmarks", nargs="*", help="Benchmark names (default: all with a results.csv)")
    parser.add_argument("--run", help="generate_mutants.py output directory (default: latest)")
    parser.add_argument("--survivors", action="store_true", help="List surviving mutants per approach")
    parser.add_argument("--no-plot", action="store_true", help="Skip plotting")
    args = parser.parse_args()

    run_dir = resolve_run_dir(args.run)
    names = args.benchmarks or sorted(
        d for d in os.listdir(run_dir) if os.path.isfile(os.path.join(run_dir, d, "results.csv")))
    if not names:
        sys.exit("No results found in " + run_dir)

    reports = [analyze(Benchmark.load(run_dir, name)) for name in names]
    for report in reports:
        print_report(report, args.survivors)
    print("=" * 78)
    for file_name, attr in (("summary.csv", "summaries"), ("per_mutant.csv", "mutants"),
                            ("agreement.csv", "agreement"), ("trace_overlap.csv", "overlap"),
                            ("proof_modes.csv", "proof_modes")):
        write_records_csv(os.path.join(run_dir, file_name), [x for r in reports for x in getattr(r, attr)])

    if not args.no_plot:
        import plots
        plots.plot_all(reports, run_dir)


if __name__ == "__main__":
    main()
