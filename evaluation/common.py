"""Paths, benchmark layout and small helpers shared by the mutation evaluation scripts."""

import csv
import dataclasses
import glob
import os
import sys
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
BENCHMARKS_DIR = os.path.join(SCRIPT_DIR, "mutation", "benchmarks")
MUTATION_OUT = os.path.join(SCRIPT_DIR, "mutation-out")

RUN_DIR_PREFIX = "mutation_"
RUN_TIMESTAMP_FORMAT = "%Y-%m-%d_%H.%M.%S"

BASELINE = "original"  # mutant id of the unmutated program

# key-multi last: its color is too close to the one of key to sit next to it in plots
APPROACHES = ["crash", "key", "runtime", "key-multi"]
KEY_APPROACHES = ("key", "key-multi")
APPROACH_LABELS = {
    "crash": "Jazzer (crashes only)",
    "key": "Jazzer + KeY",
    "runtime": "Jazzer + runtime asserts",
    "key-multi": "Jazzer + KeY (multi-trace)",
}


# ---- benchmark layout ----

def harness_file(name):
    return name + ".java"


def assert_harness_file(name):
    return name + "Assert.java"


def impl_file(name):
    return name + "Impl.java"


def benchmark_files(name):
    return [harness_file(name), assert_harness_file(name), impl_file(name)]


def mutant_source_dir(benchmark_dir, mutant):
    return os.path.join(benchmark_dir, "original" if mutant == BASELINE else os.path.join("mutants", mutant))


def corpus_run_dir(benchmark_dir, mutant, seed):
    """Jazzer run that built the corpus replayed by all approaches on (mutant, seed)."""
    return os.path.join(benchmark_dir, "corpus", mutant, "s%s" % seed)


def replay_run_dir(benchmark_dir, mutant, approach, seed):
    return os.path.join(benchmark_dir, "runs", mutant, "%s-s%s" % (approach, seed))


# ---- run directories (one per generate_mutants.py invocation) ----

def new_run_dir():
    path = os.path.join(MUTATION_OUT, RUN_DIR_PREFIX + datetime.now().strftime(RUN_TIMESTAMP_FORMAT))
    if os.path.exists(path):
        sys.exit("Run directory already exists: " + path)
    os.makedirs(path)
    return path


def resolve_run_dir(run=None):
    """`run` is a directory name in mutation-out or a path; None selects the latest run."""
    if run:
        path = run if os.path.isdir(run) else os.path.join(MUTATION_OUT, run)
        if not os.path.isdir(path):
            sys.exit("Run directory not found: " + run)
        return os.path.abspath(path)
    runs = sorted(d for d in glob.glob(os.path.join(MUTATION_OUT, RUN_DIR_PREFIX + "*")) if os.path.isdir(d))
    if not runs:
        sys.exit("No output of generate_mutants.py in " + MUTATION_OUT)
    return runs[-1]


# ---- files ----

def read_text(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write_text(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def read_csv(path, delimiter=","):
    """Rows as dicts, or None if the file does not exist."""
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter=delimiter))


def write_records_csv(path, records):
    """Writes dataclass instances (all of the same type) as CSV; floats with 4 decimals, None as empty. Fields with
    metadata {"csv": False} are left out."""
    if not records:
        return
    fields = [f.name for f in dataclasses.fields(records[0]) if f.metadata.get("csv", True)]
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(fields)
        for r in records:
            writer.writerow([fmt_csv(getattr(r, name)) for name in fields])
    print("Saved " + path)


def fmt_csv(value):
    if value is None:
        return ""
    if isinstance(value, float):
        return "%.4f" % value
    return value


# ---- misc ----

def by_id(ids):
    """Mutant ids sorted numerically, the baseline first."""
    return sorted(ids, key=lambda m: -1 if m == BASELINE else int(m))


def mean(values):
    values = list(values)
    return sum(values) / len(values) if values else None
