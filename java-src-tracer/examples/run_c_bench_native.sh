#!/bin/bash
set -e

WORK=/tmp/c-bench
rm -rf $WORK
mkdir -p $WORK
cp /mnt/c/Users/salma/java-src-tracer/examples/bench.c $WORK/
cd $WORK

SRC_DIR=/mnt/c/Users/salma/src-tracer
SRC_TRACER_INCL=$SRC_DIR/include
SRC_TRACER_LIB=$SRC_DIR/lib

gcc -O3 bench.c -o bench_bare
cpp -I$SRC_TRACER_INCL bench.c -o bench.i
python3 $SRC_DIR/instrumenter.py bench.i -o bench_inst.c 2>&1 | tail -3
gcc -O3 -D_TRACE_MODE -I$SRC_TRACER_INCL -L$SRC_TRACER_LIB bench_inst.c -o bench_binary -lsrc_tracer
gcc -O3 -D_TEXT_TRACE_MODE -I$SRC_TRACER_INCL -L$SRC_TRACER_LIB bench_inst.c -o bench_text -lsrc_tracer

echo "--- C uninstrumented (WSL native fs) 3x n=1000000 ---"
for i in 1 2 3; do ./bench_bare 1000000; done

echo "--- C binary (WSL native fs) 3x ---"
for i in 1 2 3; do ./bench_binary 1000000; done

echo "--- C text (WSL native fs) 3x ---"
for i in 1 2 3; do ./bench_text 1000000; done

echo "--- C trace sizes ---"
ls -la *.trace* 2>/dev/null | awk '{print $5, $9}'
