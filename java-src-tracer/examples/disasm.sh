#!/bin/bash
set -e
WORK=/tmp/c-disasm
mkdir -p $WORK
cp /mnt/c/Users/salma/java-src-tracer/examples/bench.c $WORK/
cd $WORK
SRC_DIR=/mnt/c/Users/salma/src-tracer
cpp -I$SRC_DIR/include bench.c -o bench.i
python3 $SRC_DIR/instrumenter.py bench.i -o bench_inst.c >/dev/null
gcc -O3 -D_TRACE_MODE -I$SRC_DIR/include -L$SRC_DIR/lib bench_inst.c -o bench_bin -lsrc_tracer
echo "--- C run() disassembly ---"
objdump -d --disassemble=run bench_bin | sed -n '/<run>:/,/^$/p'
