"""
Generate source-code mutants of a benchmark program with the Major mutation framework.

A benchmark lives in evaluation/mutation/benchmarks/<Name>/ and consists of
  <Name>Impl.java    class <Name>Impl: the methods under test (the only file that is mutated); the
                     method under test has a JML contract, which KeY verifies along each trace
  <Name>.java        class <Name>: fuzzerTestOneInput harness (crash and key oracles; not traced)
  <Name>Assert.java  class <Name>: same harness + Java asserts equivalent to the JML contract
                     (runtime oracle for Jazzer)
Both harnesses call into <Name>Impl, so a mutant of <Name>Impl.java is the same bug for
every approach. Major does not mutate comments, so the JML contract and the asserts (the
oracles) are never mutated.

Major mutates <Name>Impl.java; every exported mutant that is not a duplicate is compiled
with javac. Before that, both harnesses are compiled against the unmutated <Name>Impl.java.

Every invocation writes to a new directory evaluation/mutation-out/mutation_<timestamp>/
(timestamp like 2026-11-01_17.23.31), so evaluation results of earlier mutant sets are
never mixed with new mutants. Output per benchmark (mutation_<timestamp>/<Name>/):
  original/     <Name>.java, <Name>Assert.java, <Name>Impl.java   unmutated baseline
  mutants/<id>/ <Name>.java, <Name>Assert.java (unchanged copies), <Name>Impl.java (mutated)
  invalid/<id>/ <Name>Impl.java, javac.log                        mutants that do not compile
  mutants.csv   id,operator,method,line,offset,transformation,status,duplicate_of
                offset: character offset of the mutated expression in <Name>Impl.java
                status: valid        compiles, used by run_mutation_eval.py
                        duplicate    same source as mutant <duplicate_of>
                        not_exported Major exported no source file for the mutant
                        invalid      <Name>Impl.java does not compile
  major/        Major's working directory (mutants.log, raw exported mutants)

Major's mutator is a javac plugin; this script invokes javac directly (the same way
tools/major/bin/major does), so it also works on Windows.

Usage:
  python generate_mutants.py Max
  python generate_mutants.py Max Quicksort BinarySearch
  python generate_mutants.py Max --javac "C:\\Users\\me\\.jdks\\jdk-11\\bin\\javac.exe"
  python generate_mutants.py Max --mml path/to/custom.mml.bin --jobs 8
"""

import argparse
import csv
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
BENCHMARKS_DIR = os.path.join(SCRIPT_DIR, "mutation", "benchmarks")
MUTATION_OUT = os.path.join(SCRIPT_DIR, "mutation-out")
MAJOR_HOME = os.path.join(PROJECT_ROOT, "tools", "major")
DEFAULT_MML = os.path.join(MAJOR_HOME, "mml", "all.mml.bin")

RUN_DIR_PREFIX = "mutation_"
RUN_TIMESTAMP_FORMAT = "%Y-%m-%d_%H.%M.%S"

# Same --add-opens flags as tools/major/bin/major (needed for javac >= 9)
ADD_OPENS = [
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED" % pkg
    for pkg in ("api", "code", "comp", "file", "jvm", "main", "processing", "tree", "util")
]

MUTANTS_CSV_FIELDS = ["id", "operator", "method", "line", "offset", "transformation", "status", "duplicate_of"]


# ---- benchmark layout (also used by run_mutation_eval.py) ----

def harness_file(name):
    return name + ".java"


def assert_harness_file(name):
    return name + "Assert.java"


def impl_file(name):
    return name + "Impl.java"


def benchmark_files(name):
    return [harness_file(name), assert_harness_file(name), impl_file(name)]


# ---- run directories (also used by run_mutation_eval.py and analyze_mutation_eval.py) ----

def new_run_dir():
    path = os.path.join(MUTATION_OUT, RUN_DIR_PREFIX + datetime.now().strftime(RUN_TIMESTAMP_FORMAT))
    if os.path.exists(path):
        sys.exit("Run directory already exists: " + path)
    os.makedirs(path)
    return path


def resolve_run_dir(run=None):
    """Directory of a generate_mutants.py run. `run` is a directory name in mutation-out
    (e.g. mutation_2026-11-01_17.23.31) or a path; None selects the latest run."""
    if run:
        path = run if os.path.isdir(run) else os.path.join(MUTATION_OUT, run)
        if not os.path.isdir(path):
            sys.exit("Run directory not found: " + run)
        return os.path.abspath(path)
    # the timestamp format sorts chronologically
    runs = sorted(d for d in glob.glob(os.path.join(MUTATION_OUT, RUN_DIR_PREFIX + "*")) if os.path.isdir(d))
    if not runs:
        sys.exit("No output of generate_mutants.py in " + MUTATION_OUT)
    return runs[-1]


# ---- javac / Major ----

def default_javac():
    java_home = os.environ.get("JAVA_HOME")
    exe = "javac.exe" if os.name == "nt" else "javac"
    if java_home and os.path.isfile(os.path.join(java_home, "bin", exe)):
        return os.path.join(java_home, "bin", exe)
    return "javac"


def javac_is_java8(javac):
    out = subprocess.run([javac, "-version"], capture_output=True, text=True)
    version = (out.stdout + out.stderr).strip()
    return bool(re.search(r"javac 1\.8", version))


def parse_mutants_log(path):
    """mutants.log: id:operator:original:replacement:method:line:offset:transformation (colon-separated).

    Only the transformation (last column) can contain colons, so split at most 7 times."""
    mutants = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if not line:
                continue
            parts = line.split(":", 7)
            if len(parts) < 8:
                continue
            mutants.append({
                "id": parts[0],
                "operator": parts[1],
                "method": parts[4],
                "line": parts[5],
                "offset": parts[6],
                "transformation": parts[7],
            })
    return mutants


def run_major(src_file, work_dir, javac, mml):
    file_name = os.path.basename(src_file)
    os.makedirs(work_dir)
    shutil.copy(src_file, os.path.join(work_dir, file_name))
    # Use relative paths in the plugin argument: Major splits options at ':', which
    # would break absolute Windows paths (C:\...).
    shutil.copy(mml, os.path.join(work_dir, "mutation.mml.bin"))
    classes_dir = os.path.join(work_dir, "classes")
    os.makedirs(classes_dir)

    plugin_arg = ("-Xplugin:MajorPlugin mml:mutation.mml.bin export.mutants strict.checks "
                  "mutants.directory:mutants mutants.log:mutants.log")
    cmd = [javac]
    if not javac_is_java8(javac):
        cmd += ADD_OPENS
    cmd += ["-cp", os.path.join(MAJOR_HOME, "lib", "major.jar"), plugin_arg,
            "-d", "classes", file_name]

    print("Running Major on %s ..." % src_file)
    result = subprocess.run(cmd, cwd=work_dir, capture_output=True, text=True)
    with open(os.path.join(work_dir, "major.log"), "w", encoding="utf-8") as f:
        f.write(" ".join(cmd) + "\n\n" + result.stdout + result.stderr)
    print("  " + (result.stdout + result.stderr).strip().replace("\n", "\n  "))
    if result.returncode != 0:
        sys.exit("Major failed (exit code %d), see %s" % (result.returncode, os.path.join(work_dir, "major.log")))

    log = os.path.join(work_dir, "mutants.log")
    if not os.path.isfile(log):
        sys.exit("Major produced no mutants.log in " + work_dir)
    return log


def find_exported_mutant(work_dir, mutant_id, file_name):
    matches = glob.glob(os.path.join(work_dir, "mutants", mutant_id, "**", file_name), recursive=True)
    return matches[0] if matches else None


def javac_errors(javac, sources):
    """Compiles {file name: source text} together; javac output if that fails, otherwise None."""
    tmp = tempfile.mkdtemp(prefix="mutant-check-")
    try:
        paths = []
        for file_name, text in sources.items():
            paths.append(os.path.join(tmp, file_name))
            write_text(paths[-1], text)
        out = os.path.join(tmp, "classes")
        os.makedirs(out)
        result = subprocess.run([javac, "-nowarn", "-d", out] + paths, capture_output=True, text=True)
        if result.returncode == 0:
            return None
        output = result.stdout + result.stderr
        for path in paths:
            output = output.replace(path, os.path.basename(path))
        return output
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---- main ----

def read_text(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write_text(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def check_harnesses_compile(name, sources, javac):
    """Exit if a harness does not compile against the unmutated <Name>Impl.java.
    The harnesses are compiled separately because both declare class <Name>."""
    for harness in (harness_file(name), assert_harness_file(name)):
        errors = javac_errors(javac, {harness: sources[harness], impl_file(name): sources[impl_file(name)]})
        if errors:
            sys.exit("%s does not compile together with %s:\n%s" % (harness, impl_file(name), errors))


def generate(name, run_dir, javac, mml, jobs):
    bench_dir = os.path.join(BENCHMARKS_DIR, name)
    sources = {f: read_text(os.path.join(bench_dir, f)) for f in benchmark_files(name)}
    check_harnesses_compile(name, sources, javac)

    out_dir = os.path.join(run_dir, name)
    work_dir = os.path.join(out_dir, "major")
    mutants_dir = os.path.join(out_dir, "mutants")
    invalid_dir = os.path.join(out_dir, "invalid")
    original_dir = os.path.join(out_dir, "original")
    os.makedirs(mutants_dir)
    os.makedirs(original_dir)
    for file_name, text in sources.items():
        write_text(os.path.join(original_dir, file_name), text)

    log = run_major(os.path.join(bench_dir, impl_file(name)), work_dir, javac, mml)
    mutants = parse_mutants_log(log)

    seen_sources = {}
    candidates = []  # (mutant, mutated <Name>Impl.java), still to be compiled
    for m in mutants:
        m["duplicate_of"] = ""
        mutant_src = find_exported_mutant(work_dir, m["id"], impl_file(name))
        if mutant_src is None:
            m["status"] = "not_exported"
            continue
        mutant_text = read_text(mutant_src)
        digest = hashlib.sha1(mutant_text.encode("utf-8")).hexdigest()
        if digest in seen_sources:
            m["status"] = "duplicate"
            m["duplicate_of"] = seen_sources[digest]
            continue
        seen_sources[digest] = m["id"]
        candidates.append((m, mutant_text))

    def compile_candidate(candidate):
        return javac_errors(javac, {impl_file(name): candidate[1]})

    print("Compiling %d mutant(s) ..." % len(candidates))
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        compile_output = list(pool.map(compile_candidate, candidates))

    for (m, mutant_text), errors in zip(candidates, compile_output):
        if errors:
            m["status"] = "invalid"
            target = os.path.join(invalid_dir, m["id"])
            os.makedirs(target)
            write_text(os.path.join(target, "javac.log"), errors)
        else:
            m["status"] = "valid"
            target = os.path.join(mutants_dir, m["id"])
            os.makedirs(target)
            # unchanged harnesses next to the mutant, so every mutant directory is self-contained
            for harness in (harness_file(name), assert_harness_file(name)):
                write_text(os.path.join(target, harness), sources[harness])
        write_text(os.path.join(target, impl_file(name)), mutant_text)

    with open(os.path.join(out_dir, "mutants.csv"), "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=MUTANTS_CSV_FIELDS)
        writer.writeheader()
        for m in mutants:
            writer.writerow({k: m[k] for k in MUTANTS_CSV_FIELDS})

    counts = Counter(m["status"] for m in mutants)
    print("%s: %d mutants generated by Major -> %s" % (
        name, len(mutants), ", ".join("%s: %d" % (k, v) for k, v in sorted(counts.items()))))
    print("  Output in " + out_dir)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("benchmarks", nargs="*",
                        help="Benchmark names (subdirectories of evaluation/mutation/benchmarks). Default: all")
    parser.add_argument("--javac", default=default_javac(),
                        help="javac executable used for Major and the compile checks (default: $JAVA_HOME/bin/javac or javac)")
    parser.add_argument("--mml", default=DEFAULT_MML,
                        help="Compiled Major mml file (default: tools/major/mml/all.mml.bin)")
    parser.add_argument("--jobs", type=int, default=4,
                        help="Number of javac processes for the compile checks run in parallel (default: 4)")
    args = parser.parse_args()

    if not os.path.isfile(os.path.join(MAJOR_HOME, "lib", "major.jar")):
        sys.exit("Major not found in " + MAJOR_HOME)
    if not os.path.isfile(args.mml):
        sys.exit("mml file not found: " + args.mml)

    names = args.benchmarks or sorted(
        d for d in os.listdir(BENCHMARKS_DIR) if os.path.isdir(os.path.join(BENCHMARKS_DIR, d)))
    if not names:
        sys.exit("No benchmarks found in " + BENCHMARKS_DIR)
    for name in names:
        for f in benchmark_files(name):
            if not os.path.isfile(os.path.join(BENCHMARKS_DIR, name, f)):
                sys.exit("Missing benchmark file: " + os.path.join(BENCHMARKS_DIR, name, f))

    run_dir = new_run_dir()
    print("Writing mutants to " + run_dir)
    for name in names:
        generate(name, run_dir, args.javac, args.mml, args.jobs)


if __name__ == "__main__":
    main()
