"""
Run the mutation evaluation: replay a fixed corpus on every mutant with each approach.

Per mutant and seed, Jazzer first fuzzes <Name>Assert.java for --duration seconds; its corpus (plus the input of its
finding, if any) is then replayed on that mutant by every approach, so the approaches differ only in their oracle:
  crash      corpus run on <Name>.java; crashes, uncaught exceptions and timeouts count
  runtime    corpus run on <Name>Assert.java; failing Java asserts count as well
  key        as crash, plus one KeY proof per trace of the method under test (--key-timeout each)
  key-multi  as crash, plus one KeY proof over all traces (multi-trace retracing, --key-budget)
The unmutated program runs first; any finding on it aborts the evaluation (the oracle or harness is wrong).

Prerequisites: python generate_mutants.py <Name>, gradlew installDist :runtime:jar

Output next to the mutants (evaluation/mutation-out/mutation_<timestamp>/<Name>/):
  results.csv       one row per (mutant, approach, seed): start, wall_s, wall_limit, exit_code. Finished rows are
                    skipped on restart, so an interrupted evaluation resumes. See results.py for the analysis.
  eval_config.json  parameters of the runs (resuming with other values is refused) and every invocation
  corpus/<mutant>/s<seed>/              Jazzer run that built the corpus
  runs/<mutant>/<approach>-s<seed>/     fuzz command output (replay-results.csv, key-results.csv, traces/, tool.log)

Usage:
  python run_mutation_eval.py Max
  python run_mutation_eval.py Max --mutants 1-20 --approaches crash,runtime
  python run_mutation_eval.py Quicksort --duration 60 --seeds 1,2,3 --jobs 2
"""

import argparse
import csv
import glob
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from datetime import datetime

from common import (APPROACHES, BASELINE, PROJECT_ROOT, by_id, corpus_run_dir, mutant_source_dir, read_csv,
                    read_text, replay_run_dir, resolve_run_dir, write_text)
from results import NO_KEY_RESULT, RESULT_FIELDS, Run

CLI = os.path.join(PROJECT_ROOT, "build", "install", "java-src-tracer", "bin",
                   "java-src-tracer.bat" if os.name == "nt" else "java-src-tracer")

FINDING_GLOBS = ("crash-*", "timeout-*", "oom-*")
FINDING_PATTERNS = [  # (jazzer.log pattern, reason)
    ("ERROR: libFuzzer: timeout", "timeout"),
    ("ERROR: libFuzzer: out-of-memory", "oom"),
    ("ERROR: libFuzzer: deadly signal", "deadly_signal"),
]

# options that change the outcome of a run; all rows of a results.csv must be produced with the same values
RESULT_PARAMETERS = ["duration", "jazzer_timeout", "max_len", "key_timeout", "key_memory", "key_jobs", "key_budget",
                     "startup_slack"]


# ---- configuration ----

def git_commit():
    """HEAD of the tool's repository, with '-dirty' for uncommitted changes; None without git."""
    try:
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=PROJECT_ROOT, capture_output=True, text=True)
        status = subprocess.run(["git", "status", "--porcelain"], cwd=PROJECT_ROOT, capture_output=True, text=True)
    except OSError:
        return None
    if head.returncode != 0:
        return None
    return head.stdout.strip() + ("-dirty" if status.stdout.strip() else "")


def check_config(bench_dir, results_file, args):
    """Refuses to resume results.csv with other RESULT_PARAMETERS; records the invocation in eval_config.json."""
    path = os.path.join(bench_dir, "eval_config.json")
    parameters = {p: getattr(args, p) for p in RESULT_PARAMETERS}
    if not os.path.isfile(results_file):
        config = {"parameters": parameters, "sessions": []}
    else:
        with open(path, encoding="utf-8") as f:
            config = json.load(f)
        changed = [p for p in RESULT_PARAMETERS if config["parameters"].get(p) != parameters[p]]
        if changed:
            sys.exit("%s was produced with other parameters:\n%s\n"
                     "Resume with the original values, or delete results.csv (and runs/) to start over." % (
                         results_file, "\n".join("  --%s: %s (results.csv) vs. %s (now)" % (
                             p.replace("_", "-"), config["parameters"].get(p), parameters[p]) for p in changed)))
    config["sessions"].append({
        "started": datetime.now().isoformat(timespec="seconds"),
        "git_commit": git_commit(),
        "jobs": args.jobs,
        "java_home": args.java_home,
        "approaches": args.approaches,
        "seeds": args.seeds,
        "mutants": by_id(args.mutants) if args.mutants else None,
    })
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)


# ---- processes ----

class Interrupted(Exception):
    """The run was stopped by Ctrl+C; it is not recorded in results.csv."""


_running = set()  # running fuzz commands; killed with their child processes (Jazzer, KeY) on Ctrl+C
_running_lock = threading.Lock()
_stopping = threading.Event()


def kill_tree(proc):
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    proc.wait()


def kill_running():
    with _running_lock:
        _stopping.set()
        procs = list(_running)
    for proc in procs:
        kill_tree(proc)


def run_fuzz(cmd, wall_limit, run_dir, env):
    """Runs a fuzz command in a fresh run_dir; returns the fields of its results.csv row (without mutant etc.)."""
    if os.path.isdir(run_dir):
        shutil.rmtree(run_dir)
    os.makedirs(run_dir)
    # own process group, so Ctrl+C only reaches this script, which then kills the group (kill_running)
    group = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt"
             else {"start_new_session": True})
    start = time.time()
    with open(os.path.join(run_dir, "tool.log"), "w", encoding="utf-8") as log:
        log.write(" ".join(cmd) + "\n\n")
        log.flush()
        with _running_lock:
            if _stopping.is_set():
                raise Interrupted()
            proc = subprocess.Popen(cmd, cwd=PROJECT_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, **group)
            _running.add(proc)
        hit_limit = False
        try:
            proc.wait(timeout=wall_limit)
        except subprocess.TimeoutExpired:
            hit_limit = True
            kill_tree(proc)
        finally:
            with _running_lock:
                _running.discard(proc)
    if _stopping.is_set():
        raise Interrupted()
    return {"start": "%.3f" % start, "wall_s": "%.1f" % (time.time() - start), "wall_limit": hit_limit,
            "exit_code": proc.returncode}


def run_parallel(tasks, job, jobs, on_done):
    """Runs job(*task) for every task on `jobs` threads, calling on_done(task, result or exception) as tasks finish.
    Ctrl+C kills the running fuzz commands."""
    pool = ThreadPoolExecutor(max_workers=jobs)
    try:
        futures = {pool.submit(job, *t): t for t in tasks}
        pending = set(futures)
        while pending:
            finished, pending = wait(pending, timeout=1, return_when=FIRST_COMPLETED)  # timeout: Ctrl+C on Windows
            for fut in finished:
                on_done(futures[fut], fut.exception() or fut.result())
    except KeyboardInterrupt:
        print("Interrupted, killing the running fuzz commands ...")
        pool.shutdown(wait=False, cancel_futures=True)
        kill_running()
        pool.shutdown(wait=True)
        raise
    pool.shutdown()


# ---- corpus ----

def jazzer_finding(run_dir):
    """What Jazzer found in run_dir (assert, timeout, oom, an exception class, ...), or None."""
    log_path = os.path.join(run_dir, "jazzer.log")
    log = read_text(log_path) if os.path.isfile(log_path) else ""
    m = re.search(r"== Java Exception: ([\w.$]+)", log)
    if m:
        return "assert" if m.group(1) == "java.lang.AssertionError" else m.group(1)
    for pattern, reason in FINDING_PATTERNS:
        if pattern in log:
            return reason
    files = [f for p in FINDING_GLOBS for f in glob.glob(os.path.join(run_dir, p))]
    return os.path.basename(files[0]).split("-", 1)[0] if files else None


def build_corpora(name, bench_dir, mutants, args, env):
    """Lets Jazzer build the corpus of every (mutant, seed) that has none yet (asserts as oracle). Its finding is
    added to the corpus, so every approach also replays it. A finding on the unmutated program aborts."""
    todo = [(m, s) for m in mutants for s in args.seeds
            if not os.path.isfile(os.path.join(corpus_run_dir(bench_dir, m, s), "done"))]
    if not todo:
        return
    print("%s: fuzzing for the corpus (%d run(s)) ..." % (name, len(todo)))

    def job(mutant, seed):
        run_dir = corpus_run_dir(bench_dir, mutant, seed)
        cmd = [CLI, "fuzz", mutant_source_dir(bench_dir, mutant), "--oracle", "runtime", "-o", run_dir,
               "--duration", str(args.duration), "--seed", str(seed), "--jazzer-timeout", str(args.jazzer_timeout)]
        if args.max_len is not None:
            cmd += ["--max-len", str(args.max_len)]
        result = run_fuzz(cmd, args.duration + args.startup_slack, run_dir, env)
        reason = jazzer_finding(run_dir)
        corpus = os.path.join(run_dir, "corpus")
        if reason and mutant == BASELINE:
            raise RuntimeError("Jazzer reports %s on the unmutated %s - fix the benchmark first (see %s)"
                               % (reason, name, run_dir))
        # with a finding, Jazzer exits with a non-zero code
        if result["wall_limit"] or not os.path.isdir(corpus) or (result["exit_code"] != 0 and not reason):
            raise RuntimeError("fuzzing failed (%s, see %s)" % (result, run_dir))
        # the finding keeps its timestamp, so it is replayed after the entries found before it
        for f in (f for p in FINDING_GLOBS for f in glob.glob(os.path.join(run_dir, p))):
            shutil.copy2(f, os.path.join(corpus, os.path.basename(f)))
        write_text(os.path.join(run_dir, "done"), reason or "")
        return "%d corpus entries%s (%ss)" % (len(os.listdir(corpus)), ", Jazzer found " + reason if reason else "",
                                              result["wall_s"])

    errors = []

    def on_done(task, result):
        if isinstance(result, Exception):
            errors.append(result)
        else:
            print("  mutant %s seed %s: %s" % (*task, result))

    run_parallel(todo, job, args.jobs, on_done)
    if errors:
        sys.exit("%s: could not build the corpus:\n%s" % (name, "\n".join("  %s" % e for e in errors)))


# ---- replay ----

def replay_command(bench_dir, mutant, approach, seed, run_dir, args):
    """(fuzz command replaying the corpus with the approach's oracle, wall-clock limit)."""
    cmd = [CLI, "fuzz", mutant_source_dir(bench_dir, mutant),
           "--oracle", "key" if approach == "key-multi" else approach,
           "-o", run_dir,
           "--corpus", os.path.join(corpus_run_dir(bench_dir, mutant, seed), "corpus"),
           "--jazzer-timeout", str(args.jazzer_timeout)]
    wall_limit = args.duration + args.startup_slack + args.jazzer_timeout
    if approach == "key":
        cmd += ["--proof-mode", "per-trace", "--key-timeout", str(args.key_timeout), "--key-memory", args.key_memory,
                "--key-jobs", str(args.key_jobs)]
        wall_limit += args.key_budget
    elif approach == "key-multi":
        cmd += ["--proof-mode", "multi-trace", "--key-timeout", str(args.key_budget), "--key-memory", args.key_memory]
        wall_limit += args.key_budget
    return cmd, wall_limit


class ResultsFile:
    """results.csv, appended to (thread-safe) as runs finish."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        rows = read_csv(path)
        if rows is not None and rows and list(rows[0]) != RESULT_FIELDS:
            sys.exit("%s has other columns than this script writes - delete it (and runs/) to start over." % path)
        self.done = {(r["mutant"], r["approach"], r["seed"]) for r in rows or []}
        new = rows is None
        self.file = open(path, "a", encoding="utf-8", newline="")
        self.writer = csv.DictWriter(self.file, fieldnames=RESULT_FIELDS)
        if new:
            self.writer.writeheader()
            self.file.flush()

    def write(self, row):
        with self.lock:
            self.writer.writerow(row)
            self.file.flush()

    def close(self):
        self.file.close()


def check_baseline(name, bench_dir, results, args):
    """A finding on the unmutated program means the oracle or harness is wrong (false positive)."""
    runs = [Run.load(bench_dir, r) for r in read_csv(results.path) if r["mutant"] == BASELINE]
    for r in runs:
        no_result = {s: r.count(s) for s in NO_KEY_RESULT if r.count(s)}
        if r.status != "survived" or no_result:
            print("  baseline %s seed %s: %s %s %s" % (r.approach, r.seed, r.status, r.reason or "-", no_result or ""))
    if any(r.killed for r in runs) and not args.allow_baseline_findings:
        sys.exit("The unmutated %s is flagged as buggy - fix the benchmark/oracle first (or pass "
                 "--allow-baseline-findings). Remove the 'original' rows from %s to re-run the baseline."
                 % (name, results.path))


def evaluate(name, args, env):
    bench_dir = os.path.join(args.run_dir, name)
    mutants_csv = read_csv(os.path.join(bench_dir, "mutants.csv"))
    if mutants_csv is None:
        sys.exit("No mutants.csv in %s - run generate_mutants.py first" % bench_dir)
    mutants = [r["id"] for r in mutants_csv if r["status"] == "valid"]
    if args.mutants:
        mutants = [m for m in mutants if m in args.mutants]

    results_file = os.path.join(bench_dir, "results.csv")
    check_config(bench_dir, results_file, args)
    results = ResultsFile(results_file)

    def job(mutant, approach, seed):
        run_dir = replay_run_dir(bench_dir, mutant, approach, seed)
        cmd, wall_limit = replay_command(bench_dir, mutant, approach, seed, run_dir, args)
        row = {"mutant": mutant, "approach": approach, "seed": seed, **run_fuzz(cmd, wall_limit, run_dir, env)}
        if args.cleanup:
            for sub in ("key-inputs", "traces", "corpus"):
                shutil.rmtree(os.path.join(run_dir, sub), ignore_errors=True)
        results.write(row)
        return Run.load(bench_dir, {k: str(v) for k, v in row.items()})

    def replay(mutants, label):
        tasks = [(m, a, s) for m in mutants for a in args.approaches for s in args.seeds
                 if (m, a, s) not in results.done]
        if not tasks:
            return
        build_corpora(name, bench_dir, by_id({m for m, _, _ in tasks}), args, env)
        print("%s: %d run(s) %s ..." % (name, len(tasks), label))
        finished = 0

        def on_done(task, run):
            nonlocal finished
            finished += 1
            if isinstance(run, Exception):
                print("  [%d/%d] mutant %s %s seed %s: FAILED (%s)" % (finished, len(tasks), *task, run))
                return
            first = run.first_kill
            print("  [%d/%d] mutant %s %-9s seed %s: %s %s (%.0fs, input %s/%d)" % (
                finished, len(tasks), *task, run.status, run.reason, run.wall_s,
                first[1] + 1 if first else "-", run.corpus_size))

        run_parallel(tasks, job, args.jobs, on_done)

    try:
        replay([BASELINE], "on the unmutated program")
        check_baseline(name, bench_dir, results, args)
        replay(mutants, "on %d mutant(s)" % len(mutants))
    finally:
        results.close()
    print("Results in " + results_file)


def parse_id_list(spec):
    """'1,5-9' -> {'1', '5', '6', '7', '8', '9'}"""
    ids = set()
    for part in filter(None, (p.strip() for p in spec.split(","))):
        lo, _, hi = part.partition("-")
        ids.update(str(i) for i in range(int(lo), int(hi or lo) + 1))
    return ids


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("benchmarks", nargs="+", help="Benchmark names (as passed to generate_mutants.py)")
    parser.add_argument("--run", help="generate_mutants.py output directory (default: latest)")
    parser.add_argument("--approaches", default=",".join(APPROACHES),
                        help="Comma-separated subset of %s (default: all)" % ",".join(APPROACHES))
    parser.add_argument("--mutants", help="Mutant ids to run, e.g. '1,5-9' (default: all valid mutants)")
    parser.add_argument("--seeds", default="1", help="Comma-separated Jazzer seeds (default: 1)")
    parser.add_argument("--duration", type=int, default=30, help="Jazzer fuzzing time per corpus in seconds (default: 30)")
    parser.add_argument("--jazzer-timeout", type=int, default=5, help="Per-input timeout in seconds (default: 5)")
    parser.add_argument("--max-len", type=int, help="Maximum input length generated by Jazzer (default: Jazzer's)")
    parser.add_argument("--key-timeout", type=int, default=120, help="Timeout per KeY proof of key (default: 120)")
    parser.add_argument("--key-memory", default="4g", help="JVM heap per KeY process (default: 4g)")
    parser.add_argument("--key-jobs", type=int, default=1, help="Parallel KeY proofs per key run (default: 1)")
    parser.add_argument("--key-budget", type=int, default=900,
                        help="Extra wall-clock seconds of a KeY run; also the timeout of the key-multi proof (default: 900)")
    parser.add_argument("--startup-slack", type=int, default=60,
                        help="Extra wall-clock seconds per run for JVM startup and compilation (default: 60)")
    parser.add_argument("--jobs", type=int, default=1, help="Runs executed in parallel (default: 1)")
    parser.add_argument("--java-home", help="JDK used for the tool, Jazzer and KeY")
    parser.add_argument("--cleanup", action="store_true", help="Delete traces and KeY inputs after each run")
    parser.add_argument("--allow-baseline-findings", action="store_true",
                        help="Continue even if the unmutated program is flagged")
    args = parser.parse_args()

    args.approaches = [a.strip() for a in args.approaches.split(",") if a.strip()]
    unknown = set(args.approaches) - set(APPROACHES)
    if unknown:
        sys.exit("Unknown approach(es): %s (expected %s)" % (", ".join(unknown), ", ".join(APPROACHES)))
    args.seeds = [s.strip() for s in args.seeds.split(",") if s.strip()]
    args.mutants = parse_id_list(args.mutants) if args.mutants else None
    args.run_dir = resolve_run_dir(args.run)
    print("Using mutants from " + args.run_dir)
    if not os.path.isfile(CLI):
        sys.exit("CLI not found: %s\nRun: gradlew installDist :runtime:jar" % CLI)

    env = dict(os.environ)
    if args.java_home:
        env["JAVA_HOME"] = args.java_home
        env["PATH"] = os.path.join(args.java_home, "bin") + os.pathsep + env.get("PATH", "")

    try:
        for name in args.benchmarks:
            evaluate(name, args, env)
    except KeyboardInterrupt:
        sys.exit("Interrupted. The interrupted runs are not in results.csv; rerun the same command to resume.")


if __name__ == "__main__":
    main()
