#!/usr/bin/env pwsh
<#
.SYNOPSIS
  One-command Story 4 evaluation harness and pre-merge regression gate.
.EXAMPLE
  .\retrieval-post\scripts\run-eval.ps1
  .\retrieval-post\scripts\run-eval.ps1 -WriteBaseline
#>
param(
    [switch]$WriteBaseline
)

$ErrorActionPreference = "Stop"
$ModuleDir = Split-Path -Parent $PSScriptRoot
Push-Location $ModuleDir
try {
    if ($WriteBaseline) {
        mvn -B -q compile dependency:build-classpath "-Dmdep.outputFile=target/eval.cp"
        $cp = "target/classes;" + (Get-Content -Raw "target/eval.cp").Trim()
        java -cp $cp com.edupulse.retrievalpost.eval.EvalCliMain --write-baseline "eval/reports/baseline-report.json"
    } else {
        mvn -B test "-Dtest=EvaluationRegressionTest"
    }
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
