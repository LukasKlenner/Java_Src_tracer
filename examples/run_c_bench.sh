#!/bin/bash
set -e
cd /mnt/c/Users/salma/java-src-tracer

WORK=examples/c-bench
mkdir -p $WORK
SRC_DIR=/mnt/c/Users/salma/src-tracer
SRC_TRACER_INCL=$SRC_DIR/include
SRC_TRACER_LIB=$SRC_DIR/lib

echo '--- compile uninstrumented C (gcc -O3) ---'
gcc -O3 examples/bench.c -o $WORK/bench_bare

echo '--- preprocess + instrument C ---'
cpp -I$SRC_TRACER_INCL examples/bench.c -o $WORK/bench.i
( cd $WORK && python3 $SRC_DIR/instrumenter.py bench.i -o bench_inst.c 2>&1 | tail -5 )

echo '--- compile binary-mode instrumented C ---'
gcc -O3 -D_TRACE_MODE -I$SRC_TRACER_INCL -L$SRC_TRACER_LIB $WORK/bench_inst.c -o $WORK/bench_binary -lsrc_tracer

echo '--- compile text-mode instrumented C ---'
gcc -O3 -D_TEXT_TRACE_MODE -I$SRC_TRACER_INCL -L$SRC_TRACER_LIB $WORK/bench_inst.c -o $WORK/bench_text -lsrc_tracer 2>&1 | tail -3

echo '--- clean trace dir ---'
rm -rf ~/.src_tracer
mkdir -p ~/.src_tracer

echo '--- C uninstrumented 3x n=1000000 ---'
for i in 1 2 3; do $WORK/bench_bare 1000000; done

echo '--- C binary 3x ---'
for i in 1 2 3; do $WORK/bench_binary 1000000; done

echo '--- C text 3x ---'
for i in 1 2 3; do $WORK/bench_text 1000000; done

echo '--- C trace file sizes ---'
ls -la ~/.src_tracer/ | head -20
