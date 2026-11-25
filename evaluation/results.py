"""Data model of the mutation evaluation results.

results.csv (written by run_mutation_eval.py) only records how each fuzz command ran (start, wall-clock time, wall
limit, exit code). Everything else is derived from the files the fuzz command leaves in its run directory:
  replay-results.csv  index;entry;status;exception;run_ms;timestamp_ms   outcome of the concrete run per corpus entry
                      (status ok | exception | timeout)
  key-results.csv     entry;status;open_goals;automode_ms;nodes;timestamp_ms   KeY result per corpus entry
                      (status closed | open | step_limit | key_timeout | key_error | not_reached | prepare_error;
                      key-multi: traced per entry plus one line "traces" with the result of the proof over all traces)
  traces/<entry>.trace.txt   trace of the entry (key approaches, deleted by --cleanup)
"""

import json
import os
import re
from dataclasses import dataclass
from functools import cached_property

from common import (APPROACHES, BASELINE, KEY_APPROACHES, by_id, corpus_run_dir, read_csv, read_text,
                    replay_run_dir)

RESULT_FIELDS = ["mutant", "approach", "seed", "start", "wall_s", "wall_limit", "exit_code"]

PROOF_STATUSES = ("closed", "open", "step_limit", "key_timeout", "key_error")
NO_KEY_RESULT = ("step_limit", "key_timeout", "key_error")
MULTI_TRACE_ENTRY = "traces"  # KeyResultsWriter.ALL_TRACES

# an upper-case letter, followed by a lower-case hex number for calls, catches and array dimensions
TRACE_ELEMENT = re.compile(r"[A-Z][0-9a-f]*")


@dataclass(frozen=True)
class ReplayEntry:
    index: int  # position in the replay order
    entry: str
    status: str
    exception: str
    time_s: float  # seconds since the start of the run

    @property
    def failed(self):
        return self.status != "ok"

    @property
    def reason(self):
        if self.status == "timeout":
            return "timeout"
        return "assert" if self.exception == "java.lang.AssertionError" else self.exception


@dataclass(frozen=True)
class KeyEntry:
    entry: str
    status: str
    automode_s: float | None
    nodes: int | None
    time_s: float  # seconds since the start of the run

    @property
    def is_proof(self):
        return self.status in PROOF_STATUSES


@dataclass
class Run:
    """One fuzz command run: the corpus of (mutant, seed) replayed with an approach's oracle."""
    dir: str
    mutant: str
    approach: str
    seed: str
    start: float  # epoch seconds
    wall_s: float
    wall_limit: bool
    exit_code: int | None
    corpus_finding: str  # what Jazzer found while building the corpus ("" if nothing)
    replay: list[ReplayEntry]
    key: list[KeyEntry]

    @classmethod
    def load(cls, benchmark_dir, row):
        mutant, approach, seed = row["mutant"], row["approach"], row["seed"]
        run_dir = replay_run_dir(benchmark_dir, mutant, approach, seed)
        start = float(row["start"])

        def since_start(r):
            return int(r["timestamp_ms"]) / 1000.0 - start

        replay = [ReplayEntry(int(r["index"]), r["entry"], r["status"], r["exception"], since_start(r))
                  for r in read_csv(os.path.join(run_dir, "replay-results.csv"), ";") or []]
        key = [KeyEntry(r["entry"], r["status"],
                        int(r["automode_ms"]) / 1000.0 if r["automode_ms"] else None,
                        int(r["nodes"]) if r["nodes"] else None,
                        since_start(r))
               for r in read_csv(os.path.join(run_dir, "key-results.csv"), ";") or []]
        done = os.path.join(corpus_run_dir(benchmark_dir, mutant, seed), "done")
        return cls(dir=run_dir, mutant=mutant, approach=approach, seed=seed, start=start,
                   wall_s=float(row["wall_s"]), wall_limit=row["wall_limit"] == "True",
                   exit_code=int(row["exit_code"]) if row["exit_code"] else None,
                   corpus_finding=read_text(done).strip() if os.path.isfile(done) else "",
                   replay=replay, key=key)

    @property
    def is_key(self):
        return self.approach in KEY_APPROACHES

    @property
    def is_multi(self):
        return self.approach == "key-multi"

    # ---- concrete run ----

    @property
    def corpus_size(self):
        return len(self.replay)

    @property
    def failed_entries(self):
        return {r.entry for r in self.replay if r.failed}

    # ---- KeY ----

    @property
    def traces(self):
        """Corpus entries that reach the method under test and were traced."""
        if self.is_multi:
            return [k.entry for k in self.key if k.status == "traced"]
        return [k.entry for k in self.key if k.is_proof]

    @property
    def proofs(self):
        """per-trace: one proof per trace; key-multi: the one proof over all traces."""
        return [k for k in self.key if k.is_proof]

    def count(self, status):
        return sum(k.status == status for k in self.proofs)

    @property
    def key_found_bug(self):
        return self.count("open") > 0

    @property
    def time_to_key_s(self):
        """Seconds until the first proof with open goals finished, or None."""
        return min((k.time_s for k in self.proofs if k.status == "open"), default=None)

    @property
    def key_finished_s(self):
        """Seconds until the last proof finished, or None without proofs."""
        return max((k.time_s for k in self.proofs), default=None)

    @property
    def time_to_verdict_s(self):
        """Seconds until KeY's verdict on the mutant: the first open proof if there is one, otherwise the end of the
        last proof. For key-multi both are the end of its one proof."""
        return self.time_to_key_s if self.key_found_bug else self.key_finished_s

    @property
    def key_automode_s(self):
        return sum(k.automode_s or 0.0 for k in self.proofs)

    @property
    def key_nodes(self):
        return sum(k.nodes or 0 for k in self.proofs)

    def trace(self, entry):
        """Trace elements of the entry without the end marker E, or None if the trace file is missing."""
        path = os.path.join(self.dir, "traces", entry + ".trace.txt")
        if not os.path.isfile(path):
            return None
        return tuple(e for e in TRACE_ELEMENT.findall(read_text(path)) if e != "E")

    # ---- verdict ----

    @property
    def flagged_entries(self):
        """Corpus entries flagged as buggy: failing on the concrete run or with an open per-trace proof. None for
        key-multi, whose one proof does not tell which input has the bug."""
        if self.is_multi:
            return None
        return self.failed_entries | {k.entry for k in self.proofs if k.status == "open"}

    @cached_property
    def first_kill(self):
        """(reason, replay index, seconds since start) of the first input that kills the mutant, or None. On the same
        input the concrete run wins, it finds the bug without KeY. The multi-trace proof needs every trace, so its
        index is the one of the last traced entry."""
        order = {r.entry: r.index for r in self.replay}
        candidates = [(r.index, 0, r.reason, r.time_s) for r in self.replay if r.failed]
        for k in self.proofs:
            if k.status != "open":
                continue
            if self.is_multi:
                index = max((order[e] for e in self.traces if e in order), default=None)
            else:
                index = order.get(k.entry)
            if index is not None:
                candidates.append((index, 1, "key", k.time_s))
        if not candidates:
            return None
        index, _, reason, t = min(candidates)
        return reason, index, t

    @property
    def status(self):
        """killed | wall_limit | error | survived"""
        if self.first_kill:
            return "killed"
        if self.wall_limit:
            return "wall_limit"
        if self.exit_code != 0:
            return "error"
        return "survived"

    @property
    def reason(self):
        return self.first_kill[0] if self.first_kill else ""

    @property
    def killed(self):
        return self.status == "killed"


@dataclass
class Benchmark:
    """All runs of one benchmark program."""
    name: str
    dir: str
    mutants: dict[str, dict]  # rows of mutants.csv by id
    runs: dict[tuple[str, str, str], Run]  # (mutant, approach, seed) -> run
    parameters: dict  # run_mutation_eval.py parameters (eval_config.json)

    @classmethod
    def load(cls, run_dir, name):
        bench_dir = os.path.join(run_dir, name)
        mutants = {r["id"]: r for r in read_csv(os.path.join(bench_dir, "mutants.csv"))}
        runs = {}
        for row in read_csv(os.path.join(bench_dir, "results.csv")):
            run = Run.load(bench_dir, row)
            runs[(run.mutant, run.approach, run.seed)] = run
        with open(os.path.join(bench_dir, "eval_config.json"), encoding="utf-8") as f:
            parameters = json.load(f)["parameters"]
        return cls(name, bench_dir, mutants, runs, parameters)

    @cached_property
    def valid(self):
        return by_id(m for m, r in self.mutants.items() if r["status"] == "valid")

    @cached_property
    def approaches(self):
        present = {a for _, a, _ in self.runs}
        return [a for a in APPROACHES if a in present]

    @cached_property
    def seeds(self):
        return sorted({s for _, _, s in self.runs}, key=lambda s: int(s) if s.isdigit() else s)

    def run(self, mutant, approach, seed):
        return self.runs.get((mutant, approach, seed))

    def runs_of(self, approach, mutants=None, seed=None):
        """Runs of the approach on the given mutants (default: all valid mutants), in all seeds or one."""
        seeds = self.seeds if seed is None else [seed]
        return [r for m in (self.valid if mutants is None else mutants) for s in seeds
                if (r := self.run(m, approach, s))]

    def killed(self, approach, seed=None):
        """Valid mutants killed by the approach in the seed (default: in any seed)."""
        return {r.mutant for r in self.runs_of(approach, seed=seed) if r.killed}

    def scores(self, approach):
        """Mutation score per seed with at least one run of the approach."""
        return [len(self.killed(approach, s)) / len(self.valid)
                for s in self.seeds if self.runs_of(approach, seed=s)]
