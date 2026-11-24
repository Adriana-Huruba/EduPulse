#!/usr/bin/env bash
set -euo pipefail

# One-command Story 4 evaluation harness and pre-merge regression gate.
# Usage:
#   ./retrieval-post/scripts/run-eval.sh                 # run regression gate
#   ./retrieval-post/scripts/run-eval.sh --write-baseline # refresh baseline report

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${MODULE_DIR}"

if [[ "${1:-}" == "--write-baseline" ]]; then
  mvn -B -q compile dependency:build-classpath -Dmdep.outputFile=target/eval.cp
  CP="target/classes:$(cat target/eval.cp)"
  java -cp "${CP}" com.edupulse.retrievalpost.eval.EvalCliMain --write-baseline "eval/reports/baseline-report.json"
else
  mvn -B test -Dtest=EvaluationRegressionTest
fi
