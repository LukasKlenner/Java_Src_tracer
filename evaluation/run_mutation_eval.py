"""
Run the mutation evaluation: run every mutant of a benchmark on a fixed set of inputs with each
approach and record whether the approach flags the mutant as buggy ("kills" it).

The inputs are the same for every approach on a mutant, so the approaches differ only in their oracle:
per seed, Jazzer fuzzes every mutant (and the unmutated program) once for --duration seconds; its corpus is
then replayed on that mutant with every approach (fuzz command option --corpus).
The corpus is fuzzed on <Name>Assert.java, so the fuzzer checks the asserts on every execution at full strength and
is not restricted to the inputs of the unmutated program; its finding (crash-*, timeout-*, oom-* input), if any, is
added to the corpus, so every approach also replays the input the fuzzer found the bug with.

Approaches (= values of the fuzz command's --oracle option):
  crash    every corpus entry is run on <Name>.java; only crashes / uncaught exceptions / timeouts count
  key      as crash, and for every corpus entry the method under test (the method with a JML contract
           in <Name>Impl.java) is traced and its contract is verified with KeY along the trace
  runtime  every corpus entry is run on <Name>Assert.java; Java assert statements as runtime oracle

Each mutant directory contains the two unchanged harnesses <Name>.java and <Name>Assert.java
next to the mutated <Name>Impl.java. The fuzz command is given the directory; it uses
<Name>.java + <Name>Impl.java and swaps in <Name>Assert.java for the runtime oracle.
A corpus entry that exceeds --jazzer-timeout (e.g. an infinite loop in a mutant) kills the mutant;
the remaining entries are skipped.

Prerequisites:
  python generate_mutants.py <Name>            (creates evaluation/mutation-out/mutation_<timestamp>/<Name>/)
  gradlew installDist :runtime:jar             (creates build/install/java-src-tracer/)

The corpus runs of the unmutated program come first; a Jazzer finding on it aborts the evaluation. Then the
unmutated program is run with every approach and seed; if any approach flags it, the oracle or harness
is wrong and the evaluation aborts.

The mutants are taken from the latest generate_mutants.py run (or the one given with --run);
the results are written next to them.

Output (evaluation/mutation-out/mutation_<timestamp>/<Name>/):
  results.csv           one row per (mutant, approach, seed), appended as runs finish;
                        existing rows are skipped on restart, so an interrupted run resumes
  eval_config.json      parameters results.csv was produced with (--duration, --jazzer-timeout,
                        --max-len, --key-timeout, --key-memory, --key-jobs, --key-budget, --startup-slack);
                        resuming with different values is refused. Also lists every invocation
                        (time, git commit of the tool, --jobs, --java-home, approaches, seeds, mutants).
  corpus/<mutant>/s<seed>/   Jazzer run on the mutant ("original" for the baseline); its corpus/ (plus the
                        finding) is replayed by the runs of this mutant
  runs/<mutant>/<approach>-s<seed>/   output directory of the fuzz command (+ tool.log), with
                        replay-results.csv (outcome of the concrete run per corpus entry) and, for key,
                        key-results.csv (KeY result per corpus entry)

results.csv columns:
  program, mutant ("original" for the baseline), approach, seed,
  status          killed | survived | wall_limit | error
  reason          what killed the mutant: key, assert (AssertionError), timeout, oom,
                  or the exception class (e.g. java.lang.ArrayIndexOutOfBoundsException)
  inputs_to_kill  corpus entries (in replay order) up to and including the first one that killed the mutant
  inputs_to_key   the same for the first proof with open goals (key approach only)
  time_to_kill_s  seconds from process start to the first finding
  time_to_key_s   seconds from process start to the first proof with open goals (key approach only)
  wall_s          total wall-clock seconds of the run
  corpus_size     number of corpus entries replayed
  run_failures    number of corpus entries that failed on the concrete run (exception, failed assert, timeout)
  corpus_finding  what Jazzer found while fuzzing the mutant with the asserts on every execution
                  (assert, timeout, oom or the exception class; empty if nothing)
  key_proofs, key_open, key_step_limit, key_timeout, key_error   counts from key-results.csv
                  (key_proofs: corpus entries that reach the method under test; key_open: traces on which
                  KeY found a bug; key_timeout: traces on which KeY ran into --key-timeout)

Usage:
  python run_mutation_eval.py Max
  python run_mutation_eval.py Max --mutants 1-20 --approaches crash,runtime
  python run_mutation_eval.py Quicksort --duration 60 --seeds 1,2,3 --max-len 16 --jobs 2
  python run_mutation_eval.py Max --run mutation_2026-11-01_17.23.31
  python run_mutation_eval.py Max --java-home "C:\\Users\\me\\.jdks\\openjdk-23.0.1"
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

from generate_mutants import resolve_run_dir

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
CLI = os.path.join(PROJECT_ROOT, "build", "install", "java-src-tracer", "bin",
                   "java-src-tracer.bat" if os.name == "nt" else "java-src-tracer")

APPROACHES = ["crash", "key", "runtime"]
BASELINE = "original"

RESULT_FIELDS = [
    "program", "mutant", "approach", "seed", "status", "reason",
    "inputs_to_kill", "inputs_to_key", "time_to_kill_s", "time_to_key_s",
    "wall_s", "corpus_size", "run_failures", "corpus_finding", "key_proofs", "key_open", "key_step_limit", "key_timeout", "key_error",
]

FINDING_GLOBS = ("crash-*", "timeout-*", "oom-*")

CONFIG_FILE = "eval_config.json"

# options that change the outcome of a run; all rows of a results.csv must be produced with the same values
RESULT_PARAMETERS = ["duration", "jazzer_timeout", "max_len", "key_timeout", "key_memory", "key_jobs", "key_budget",
                     "startup_slack"]


# ---- selection ----

def parse_id_list(spec):
    """'1,5-9' -> {'1', '5', '6', '7', '8', '9'}"""
    ids = set()
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            ids.update(str(i) for i in range(int(lo), int(hi) + 1))
        else:
            ids.add(part)
    return ids


def load_valid_mutants(out_dir):
    path = os.path.join(out_dir, "mutants.csv")
    if not os.path.isfile(path):
        sys.exit("No mutants.csv in %s - run generate_mutants.py first" % out_dir)
    with open(path, encoding="utf-8", newline="") as f:
        return [row["id"] for row in csv.DictReader(f) if row["status"] == "valid"]


def load_done(results_file):
    done = set()
    if os.path.isfile(results_file):
        with open(results_file, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                done.add((row["mutant"], row["approach"], row["seed"]))
    return done


# ---- configuration ----

def git_commit():
    """HEAD of the tool's repository with '-dirty' if there are uncommitted changes, or None without git."""
    try:
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=PROJECT_ROOT, capture_output=True, text=True)
        if head.returncode != 0:
            return None
        status = subprocess.run(["git", "status", "--porcelain"], cwd=PROJECT_ROOT, capture_output=True, text=True)
    except OSError:
        return None
    return head.stdout.strip() + ("-dirty" if status.stdout.strip() else "")


def check_config(out_dir, results_file, args):
    """Exit if results.csv was produced with other RESULT_PARAMETERS than the current ones, so a resumed
    evaluation never mixes runs of different settings. Records the current invocation in eval_config.json."""
    path = os.path.join(out_dir, CONFIG_FILE)
    parameters = {p: getattr(args, p) for p in RESULT_PARAMETERS}

    if not os.path.isfile(results_file):
        # new evaluation (or results.csv was deleted to start over)
        config = {"parameters": parameters, "sessions": []}
    elif not os.path.isfile(path):
        sys.exit("%s exists but %s does not, so the parameters of its runs are unknown.\n"
                 "Delete results.csv (and runs/) to start over." % (results_file, path))
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
        "mutants": args.mutants,
    })
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)


# ---- running ----

class Interrupted(Exception):
    """The run was stopped by Ctrl+C; it is not recorded in results.csv."""


# running fuzz commands, killed with all their child processes (Jazzer, KeY)
_running = set()
_running_lock = threading.Lock()
_stopping = threading.Event()


def kill_running():
    """Kills every running fuzz command with its child processes; no new ones are started afterwards."""
    with _running_lock:
        _stopping.set()
        procs = list(_running)
    for proc in procs:
        kill_tree(proc)


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


def corpus_command(source, seed, run_dir, args):
    """Fuzz command that lets Jazzer build the corpus that the approaches replay (asserts as oracle)."""
    cmd = [CLI, "fuzz", source,
           "--oracle", "runtime",
           "-o", run_dir,
           "--duration", str(args.duration),
           "--seed", str(seed),
           "--jazzer-timeout", str(args.jazzer_timeout)]
    if args.max_len is not None:
        cmd += ["--max-len", str(args.max_len)]
    return cmd, args.duration + args.startup_slack


def replay_command(source, approach, corpus, run_dir, args):
    """Fuzz command that replays the corpus with the approach's oracle."""
    cmd = [CLI, "fuzz", source,
           "--oracle", approach,
           "-o", run_dir,
           "--corpus", corpus,
           "--jazzer-timeout", str(args.jazzer_timeout)]
    if approach == "key":
        cmd += ["--key-timeout", str(args.key_timeout), "--key-memory", args.key_memory,
                "--key-jobs", str(args.key_jobs)]
    # replaying takes far less than fuzzing the corpus took; one entry may run into --jazzer-timeout
    wall_limit = args.duration + args.startup_slack + args.jazzer_timeout + (args.key_budget if approach == "key" else 0)
    return cmd, wall_limit


def run_fuzz(cmd, wall_limit, run_dir, env):
    """Runs the fuzz command; returns (wall seconds, start epoch seconds, hit wall limit, exit code)."""
    if os.path.isdir(run_dir):
        shutil.rmtree(run_dir)
    os.makedirs(run_dir)

    # The fuzz command and its children (Jazzer, KeY) get their own process group, so Ctrl+C only reaches this
    # script, which then kills them (kill_running). On Windows this also avoids cmd's "Terminate batch job (Y/N)?".
    popen_kwargs = {}
    if os.name == "nt":
        popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_kwargs["start_new_session"] = True

    start = time.time()
    with open(os.path.join(run_dir, "tool.log"), "w", encoding="utf-8") as log:
        log.write(" ".join(cmd) + "\n\n")
        log.flush()
        with _running_lock:
            if _stopping.is_set():
                raise Interrupted()
            proc = subprocess.Popen(cmd, cwd=PROJECT_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                    **popen_kwargs)
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
    return time.time() - start, start, hit_limit, proc.returncode


# ---- classification ----

def jazzer_finding(run_dir, start):
    """Returns (reason, seconds since start) of Jazzer's first finding, or (None, None)."""
    files = []
    for pattern in FINDING_GLOBS:
        files += glob.glob(os.path.join(run_dir, pattern))

    log_text = ""
    log_path = os.path.join(run_dir, "jazzer.log")
    if os.path.isfile(log_path):
        with open(log_path, encoding="utf-8", errors="replace") as f:
            log_text = f.read()

    reason = None
    m = re.search(r"== Java Exception: ([\w.$]+)", log_text)
    if m:
        reason = "assert" if m.group(1) == "java.lang.AssertionError" else m.group(1)
    elif re.search(r"ERROR: libFuzzer: timeout", log_text):
        reason = "timeout"
    elif re.search(r"ERROR: libFuzzer: out-of-memory", log_text):
        reason = "oom"
    elif re.search(r"ERROR: libFuzzer: deadly signal", log_text):
        reason = "deadly_signal"
    elif files:
        reason = os.path.basename(files[0]).split("-", 1)[0]

    if reason is None:
        return None, None
    t = min(os.path.getmtime(f) for f in files) - start if files else None
    return reason, t


def replay_summary(run_dir, start):
    """Reads replay-results.csv; returns (entry -> replay index, number of failed entries,
    (reason, index, seconds since start) of the first failed entry or None)."""
    order, failures, first = {}, 0, None
    path = os.path.join(run_dir, "replay-results.csv")
    if os.path.isfile(path):
        with open(path, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f, delimiter=";"):
                index = int(row["index"])
                order[row["entry"]] = index
                if row["status"] == "ok":
                    continue
                failures += 1
                if first is None:
                    if row["status"] == "timeout":
                        reason = "timeout"
                    else:
                        reason = "assert" if row["exception"] == "java.lang.AssertionError" else row["exception"]
                    first = (reason, index, int(row["timestamp_ms"]) / 1000.0 - start)
    return order, failures, first


def key_summary(run_dir, start, order):
    """Counts per status in key-results.csv, (replay index, seconds since start) of the open proof whose corpus
    entry comes first in the replay order (or None) and seconds until the first open proof finished (or None)."""
    counts = {"closed": 0, "open": 0, "step_limit": 0, "key_timeout": 0, "key_error": 0, "prepare_error": 0,
              "not_reached": 0}
    first_open, t_first = None, None
    path = os.path.join(run_dir, "key-results.csv")
    if os.path.isfile(path):
        with open(path, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f, delimiter=";"):
                status = row["status"]
                counts[status] = counts.get(status, 0) + 1
                if status == "open" and row["entry"] in order:
                    t = int(row["timestamp_ms"]) / 1000.0 - start
                    candidate = (order[row["entry"]], t)
                    first_open = candidate if first_open is None else min(first_open, candidate)
                    t_first = t if t_first is None else min(t_first, t)
    return counts, first_open, t_first


def classify(program, mutant, approach, seed, run_dir, wall, start, hit_limit, exit_code, corpus_finding):
    order, run_failures, first_failure = replay_summary(run_dir, start)
    key_counts, first_open, t_key = key_summary(run_dir, start, order) if approach == "key" else ({}, None, None)

    killed_by_key = key_counts.get("open", 0) > 0
    killed_by_run = first_failure is not None

    # the oracle that needs fewer inputs kills the mutant; on the same input, the concrete run wins,
    # since it would have found the bug without KeY
    if killed_by_run and (first_open is None or first_failure[1] <= first_open[0]):
        status, kill_reason, i_kill, t_kill = "killed", first_failure[0], first_failure[1], first_failure[2]
    elif killed_by_key:
        status, kill_reason, i_kill, t_kill = "killed", "key", first_open[0], first_open[1]
    elif hit_limit:
        status, kill_reason, i_kill, t_kill = "wall_limit", "", None, None
    elif exit_code != 0 or not os.path.isfile(os.path.join(run_dir, "replay-results.csv")):
        status, kill_reason, i_kill, t_kill = "error", "", None, None
    else:
        status, kill_reason, i_kill, t_kill = "survived", "", None, None

    def key_count(name):
        return key_counts.get(name, "") if approach == "key" else ""

    return {
        "program": program,
        "mutant": mutant,
        "approach": approach,
        "seed": seed,
        "status": status,
        "reason": kill_reason,
        "inputs_to_kill": "" if i_kill is None else i_kill + 1,
        "inputs_to_key": "" if first_open is None else first_open[0] + 1,
        "time_to_kill_s": "" if t_kill is None else "%.1f" % max(t_kill, 0.0),
        "time_to_key_s": "" if t_key is None else "%.1f" % max(t_key, 0.0),
        "wall_s": "%.1f" % wall,
        "corpus_size": len(order),
        "run_failures": run_failures,
        "corpus_finding": corpus_finding,
        "key_proofs": sum(n for s, n in key_counts.items() if s != "not_reached") if approach == "key" else "",
        "key_open": key_count("open"),
        "key_step_limit": key_count("step_limit"),
        "key_timeout": key_count("key_timeout"),
        "key_error": (key_counts.get("key_error", 0) + key_counts.get("prepare_error", 0)) if approach == "key" else "",
    }


def cleanup_run_dir(run_dir):
    """Keep logs, replay-results.csv and key-results.csv; drop traces, KeY proof directories and the corpus copy."""
    for sub in ("key-inputs", "traces", "corpus"):
        shutil.rmtree(os.path.join(run_dir, sub), ignore_errors=True)


# ---- main ----

class ResultsWriter:
    def __init__(self, path):
        self.lock = threading.Lock()
        new = not os.path.isfile(path)
        if not new:
            with open(path, encoding="utf-8", newline="") as f:
                header = next(csv.reader(f), None)
            if header != RESULT_FIELDS:
                sys.exit("%s has other columns than this script writes - delete it (and runs/) to start over."
                         % path)
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


def check_baseline(name, results_file, args):
    """A finding on the unmutated program means the oracle or harness is wrong (false positive)."""
    with open(results_file, encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if r["mutant"] == BASELINE and r["approach"] in args.approaches]
    killed = [r for r in rows if r["status"] == "killed"]
    for r in rows:
        if r["status"] != "survived" or r["key_step_limit"] not in ("", "0") or r["key_timeout"] not in ("", "0"):
            print("  baseline %s seed %s: %s %s (step limit: %s, KeY timeouts: %s)" % (
                r["approach"], r["seed"], r["status"], r["reason"] or "-",
                r["key_step_limit"] or "-", r["key_timeout"] or "-"))
    if killed and not args.allow_baseline_findings:
        sys.exit("The unmutated %s is flagged as buggy - fix the benchmark/oracle first "
                 "(or pass --allow-baseline-findings). Remove the 'original' rows from %s to re-run the baseline."
                 % (name, results_file))


def corpus_run_dir(out_dir, mutant, seed):
    return os.path.join(out_dir, "corpus", mutant, "s%s" % seed)


def corpus_finding_of(run_dir):
    """What Jazzer found while building the corpus in run_dir (empty if nothing), as recorded in its done file."""
    with open(os.path.join(run_dir, "done"), encoding="utf-8") as f:
        return f.read().strip()


def build_corpora(name, out_dir, mutants, args, env):
    """Lets Jazzer build the corpora, once per mutant and seed on the mutant (asserts as oracle, the finding is
    added to the corpus). Returns (mutant, seed) -> corpus directory. Finished corpora are reused, so an interrupted evaluation replays the same
    inputs. A finding on the unmutated program aborts the evaluation."""
    corpora, todo = {}, []
    for mutant in mutants:
        for seed in args.seeds:
            run_dir = corpus_run_dir(out_dir, mutant, seed)
            corpora[(mutant, seed)] = os.path.join(run_dir, "corpus")
            if not os.path.isfile(os.path.join(run_dir, "done")):
                todo.append((mutant, seed, run_dir))
    if not todo:
        return corpora

    label = "the unmutated program" if mutants == [BASELINE] else "%d mutant(s)" % len({t[0] for t in todo})
    print("%s: fuzzing %s for the corpus (%d run(s)) ..." % (name, label, len(todo)))

    def job(mutant, seed, run_dir):
        source = os.path.join(out_dir, "original") if mutant == BASELINE else os.path.join(out_dir, "mutants", mutant)
        cmd, wall_limit = corpus_command(source, seed, run_dir, args)
        wall, start, hit_limit, exit_code = run_fuzz(cmd, wall_limit, run_dir, env)
        corpus = corpora[(mutant, seed)]
        reason, _ = jazzer_finding(run_dir, start)
        if reason is not None and mutant == BASELINE:
            raise RuntimeError("Jazzer reports %s on the unmutated %s - fix the benchmark first (see %s)"
                               % (reason, name, run_dir))
        # with a finding, Jazzer stops early and exits with a non-zero code
        if hit_limit or not os.path.isdir(corpus) or (exit_code != 0 and reason is None):
            raise RuntimeError("fuzzing failed (exit code %s, wall limit: %s, see %s)" % (exit_code, hit_limit, run_dir))
        # the input Jazzer found the bug with is not in the corpus; replay it as well (it keeps its timestamp,
        # so it comes after the corpus entries that were found before it)
        for pattern in FINDING_GLOBS:
            for finding in glob.glob(os.path.join(run_dir, pattern)):
                shutil.copy2(finding, os.path.join(corpus, os.path.basename(finding)))
        with open(os.path.join(run_dir, "done"), "w", encoding="utf-8") as f:
            f.write(reason or "")
        print("  %s seed %s: %d corpus entries%s (%.0fs)" % (
            "original" if mutant == BASELINE else "mutant " + mutant, seed, len(os.listdir(corpus)),
            ", Jazzer found " + reason if reason else "", wall))

    pool = ThreadPoolExecutor(max_workers=args.jobs)
    try:
        futures = [pool.submit(job, *t) for t in todo]
        pending = set(futures)
        while pending:
            _, pending = wait(pending, timeout=1, return_when=FIRST_COMPLETED)
        errors = [f.exception() for f in futures if f.exception() is not None]
    except KeyboardInterrupt:
        print("Interrupted, killing the running fuzz commands (Jazzer) ...")
        pool.shutdown(wait=False, cancel_futures=True)
        kill_running()
        pool.shutdown(wait=True)
        raise
    pool.shutdown()
    if errors:
        sys.exit("%s: could not build the corpus:\n%s" % (name, "\n".join("  %s" % e for e in errors)))
    return corpora


def evaluate(name, args, env):
    out_dir = os.path.join(args.run_dir, name)
    results_file = os.path.join(out_dir, "results.csv")
    mutants = load_valid_mutants(out_dir)
    if args.mutants:
        selected = parse_id_list(args.mutants)
        mutants = [m for m in mutants if m in selected]

    check_config(out_dir, results_file, args)
    done = load_done(results_file)
    corpora = {}
    results = ResultsWriter(results_file)

    def job(mutant, approach, seed):
        if mutant == BASELINE:
            source = os.path.join(out_dir, "original")
        else:
            source = os.path.join(out_dir, "mutants", mutant)
        run_dir = os.path.join(out_dir, "runs", mutant, "%s-s%s" % (approach, seed))
        cmd, wall_limit = replay_command(source, approach, corpora[(mutant, seed)], run_dir, args)
        wall, start, hit_limit, exit_code = run_fuzz(cmd, wall_limit, run_dir, env)
        row = classify(name, mutant, approach, seed, run_dir, wall, start, hit_limit, exit_code,
                       corpus_finding_of(corpus_run_dir(out_dir, mutant, seed)))
        if args.cleanup:
            cleanup_run_dir(run_dir)
        results.write(row)
        return row

    def not_done(tasks):
        return [t for t in tasks if (t[0], t[1], str(t[2])) not in done]

    def run_all(tasks, label):
        tasks = not_done(tasks)
        if not tasks:
            return []
        print("%s: %d run(s) %s ..." % (name, len(tasks), label))
        rows = []
        pool = ThreadPoolExecutor(max_workers=args.jobs)
        try:
            futures = {pool.submit(job, *t): t for t in tasks}
            pending = set(futures)
            i = 0
            while pending:
                finished, pending = wait(pending, timeout=1, return_when=FIRST_COMPLETED)
                for fut in finished:
                    i += 1
                    mutant, approach, seed = futures[fut]
                    try:
                        row = fut.result()
                    except Exception as e:
                        print("  [%d/%d] mutant %s %s seed %s: FAILED (%s)" % (i, len(tasks), mutant, approach, seed, e))
                        continue
                    rows.append(row)
                    print("  [%d/%d] mutant %s %-7s seed %s: %s %s (%ss, input %s/%s)" % (
                        i, len(tasks), mutant, approach, seed, row["status"], row["reason"], row["wall_s"],
                        row["inputs_to_kill"] or "-", row["corpus_size"]))
        except KeyboardInterrupt:
            print("Interrupted, killing the running fuzz commands (Jazzer, KeY) ...")
            pool.shutdown(wait=False, cancel_futures=True)
            kill_running()
            pool.shutdown(wait=True)
            raise
        pool.shutdown()
        return rows

    try:
        baseline_tasks = [(BASELINE, a, s) for a in args.approaches for s in args.seeds]
        if not_done(baseline_tasks):
            corpora.update(build_corpora(name, out_dir, [BASELINE], args, env))
        run_all(baseline_tasks, "on the unmutated program (baseline)")
        check_baseline(name, results_file, args)

        tasks = [(m, a, s) for m in mutants for a in args.approaches for s in args.seeds]
        corpora.update(build_corpora(name, out_dir, sorted({t[0] for t in not_done(tasks)}, key=int), args, env))
        run_all(tasks, "on %d mutant(s)" % len(mutants))
    finally:
        results.close()
    print("Results in " + results_file)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("benchmarks", nargs="+", help="Benchmark names (as passed to generate_mutants.py)")
    parser.add_argument("--run", help="generate_mutants.py output directory in evaluation/mutation-out "
                                      "(e.g. mutation_2026-11-01_17.23.31) or a path (default: latest)")
    parser.add_argument("--approaches", default=",".join(APPROACHES),
                        help="Comma-separated subset of %s (default: all)" % ",".join(APPROACHES))
    parser.add_argument("--mutants", help="Mutant ids to run, e.g. '1,5-9' (default: all valid mutants)")
    parser.add_argument("--seeds", default="1", help="Comma-separated Jazzer seeds (default: 1)")
    parser.add_argument("--duration", type=int, default=30, help="Jazzer fuzzing time per run in seconds (default: 30)")
    parser.add_argument("--jazzer-timeout", type=int, default=5,
                        help="Per-input timeout in seconds; slower inputs are reported as Jazzer timeout findings (default: 5)")
    parser.add_argument("--max-len", type=int, help="Maximum input length generated by Jazzer (default: Jazzer's 4096)")
    parser.add_argument("--key-timeout", type=int, default=120, help="Timeout per KeY proof in seconds (default: 120)")
    parser.add_argument("--key-memory", default="4g", help="JVM heap per KeY process (default: 4g)")
    parser.add_argument("--key-jobs", type=int, default=1,
                        help="KeY proofs run in parallel per key run, each with --key-memory heap; "
                             "with --jobs, up to jobs * key-jobs KeY processes run at once (default: 1)")
    parser.add_argument("--key-budget", type=int, default=900,
                        help="Extra wall-clock seconds a key run may take after fuzzing to verify the corpus (default: 900)")
    parser.add_argument("--startup-slack", type=int, default=60,
                        help="Extra wall-clock seconds per run for JVM startup and compilation (default: 60)")
    parser.add_argument("--jobs", type=int, default=1, help="Number of runs executed in parallel (default: 1)")
    parser.add_argument("--java-home", help="JDK used for the tool, Jazzer and KeY (sets JAVA_HOME and PATH)")
    parser.add_argument("--cleanup", action="store_true", help="Delete traces and KeY proof directories after each run")
    parser.add_argument("--allow-baseline-findings", action="store_true",
                        help="Continue even if the unmutated program is flagged")
    args = parser.parse_args()

    args.approaches = [a.strip() for a in args.approaches.split(",") if a.strip()]
    for a in args.approaches:
        if a not in APPROACHES:
            sys.exit("Unknown approach: %s (expected one of %s)" % (a, ", ".join(APPROACHES)))
    args.seeds = [s.strip() for s in args.seeds.split(",") if s.strip()]
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
