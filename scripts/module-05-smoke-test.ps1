#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Smoke-tests Module 5 against the local kind cluster.

.DESCRIPTION
    Verifies resilience and autoscaling:
      1. metrics-server is installed and serving the metrics API
      2. the order-service HPA spec (min 2, max 5, CPU 70%) and its metrics
      3. the PodDisruptionBudget (minAvailable 1)
      4. both order branches reach their terminal state
      5. deleting an order-service pod under load produces no failed requests
         and the deployment returns to two Ready replicas

    This script is unattended. The interactive scaling and drain walkthrough is
    scripts/module-05-drills.ps1.

    Exits 0 when every check passes, 1 otherwise.

.NOTES
    Requires kubectl on PATH and the k8s-lab cluster with the Module 3/4 stack
    deployed. See docs/module-05-resilience-autocaling.md.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$Namespace = 'k8s-lab'
$BaseUrl = 'http://localhost'

$script:Checks = 0
$script:Failures = 0

function Write-Step {
    param([string]$Title)
    Write-Host "`n== $Title" -ForegroundColor Cyan
}

function Write-Pass {
    param([string]$Message)
    $script:Checks++
    Write-Host "   PASS  $Message" -ForegroundColor Green
}

function Write-Fail {
    param([string]$Message)
    $script:Checks++
    $script:Failures++
    Write-Host "   FAIL  $Message" -ForegroundColor Red
}

function Assert-Equal {
    param($Actual, $Expected, [string]$What)
    if ("$Actual" -eq "$Expected") {
        Write-Pass "$What = $Actual"
    }
    else {
        Write-Fail "$What expected '$Expected' but was '$Actual'"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$What)
    if ($Condition) {
        Write-Pass $What
    }
    else {
        Write-Fail $What
    }
}

# Runs a read-only kubectl command and returns its stdout. Stderr is suppressed
# because kubectl prints "Defaulted container ..." for pods with init containers.
function Get-KubectlOutput {
    param([string[]]$Arguments)
    $output = & kubectl @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl $($Arguments[0]) failed with exit code $LASTEXITCODE"
    }
    return @($output)
}

function Wait-OrderStatus {
    param([string]$OrderId, [string[]]$Expected, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $status = $null
    while ((Get-Date) -lt $deadline) {
        $status = (Invoke-RestMethod -Uri "$BaseUrl/api/orders/$OrderId").status
        if ($status -in $Expected) {
            return $status
        }
        Start-Sleep -Milliseconds 500
    }
    return $status
}

function Wait-ForReadyReplicas {
    param([int]$Minimum, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $ready = @()
    do {
        $pods = & kubectl get pods -n $Namespace -l app.kubernetes.io/name=order-service --no-headers 2>$null
        $ready = @($pods | Where-Object { $_ -match '1/1\s+Running' })
        if ($ready.Count -ge $Minimum) {
            return $ready.Count
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $ready.Count
}

Push-Location (Split-Path -Parent $PSScriptRoot)
$loadJob = $null
try {
    Write-Step 'Prerequisites'
    if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
        throw 'kubectl was not found on PATH.'
    }
    $products = Invoke-RestMethod -Uri "$BaseUrl/api/products" -TimeoutSec 10
    Assert-True (($products | Measure-Object).Count -eq 5) 'the platform is reachable through the Ingress'

    Write-Step 'Deploy resilience objects'
    & kubectl apply -f infra/k8s/hpa.yaml -f infra/k8s/pdb.yaml -f infra/k8s/order-service.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for the resilience objects failed.'
    }
    & kubectl rollout status deployment/order-service -n $Namespace --timeout=300s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'order-service did not become ready.'
    }
    Write-Pass 'HPA, PDB and order-service applied'

    Write-Step 'Metrics server'
    $available = (Get-KubectlOutput @(
        'get', 'apiservice', 'v1beta1.metrics.k8s.io',
        '-o', 'jsonpath={.status.conditions[?(@.type=="Available")].status}')) -join ''
    Assert-Equal $available 'True' 'metrics API is available'

    $metricsRows = 0
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $metricsRows = @(& kubectl top pods -n $Namespace 2>$null | Where-Object { $_ -match '\S' }).Count
        if ($metricsRows -gt 0) {
            break
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    Assert-True ($metricsRows -gt 0) "kubectl top pods returns metrics ($metricsRows rows)"

    Write-Step 'Autoscaling'
    $min = (Get-KubectlOutput @('get', 'hpa', 'order-service', '-n', $Namespace, '-o', 'jsonpath={.spec.minReplicas}')) -join ''
    $max = (Get-KubectlOutput @('get', 'hpa', 'order-service', '-n', $Namespace, '-o', 'jsonpath={.spec.maxReplicas}')) -join ''
    $target = (Get-KubectlOutput @(
        'get', 'hpa', 'order-service', '-n', $Namespace,
        '-o', 'jsonpath={.spec.metrics[0].resource.target.averageUtilization}')) -join ''
    Assert-Equal $min '2' 'HPA minReplicas'
    Assert-Equal $max '5' 'HPA maxReplicas'
    Assert-Equal $target '70' 'HPA CPU target'

    $current = ''
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $current = (Get-KubectlOutput @(
            'get', 'hpa', 'order-service', '-n', $Namespace,
            '-o', 'jsonpath={.status.currentMetrics[0].resource.current.averageUtilization}')) -join ''
        if ($current -ne '') {
            break
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    Assert-True ($current -ne '') "HPA is reading CPU metrics ($current% of target)"

    Write-Step 'Disruption budget'
    $minAvailable = (Get-KubectlOutput @(
        'get', 'pdb', 'order-service', '-n', $Namespace, '-o', 'jsonpath={.spec.minAvailable}')) -join ''
    Assert-Equal $minAvailable '1' 'PDB minAvailable'
    $allowed = (Get-KubectlOutput @(
        'get', 'pdb', 'order-service', '-n', $Namespace, '-o', 'jsonpath={.status.disruptionsAllowed}')) -join ''
    Assert-True ($allowed -ne '') "PDB currently allows $allowed disruption(s)"

    Write-Step 'Order flow'
    $okUser = "resilience-ok-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/carts/$okUser/items" `
        -ContentType 'application/json' -Body '{"productId":1,"quantity":2}' | Out-Null
    $okOrder = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" `
        -ContentType 'application/json' -Body (@{ userId = $okUser } | ConvertTo-Json)
    $okStatus = Wait-OrderStatus -OrderId $okOrder.id -Expected @('PRINTED', 'PAYMENT_FAILED')
    Assert-Equal $okStatus 'PRINTED' 'valid order reaches PRINTED'

    $bigUser = "resilience-big-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/carts/$bigUser/items" `
        -ContentType 'application/json' -Body '{"productId":1,"quantity":401}' | Out-Null
    $bigOrder = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" `
        -ContentType 'application/json' -Body (@{ userId = $bigUser } | ConvertTo-Json)
    Assert-Equal ([decimal]$bigOrder.totalAmount) 1002.50 'declined order total'
    $bigStatus = Wait-OrderStatus -OrderId $bigOrder.id -Expected @('PRINTED', 'PAYMENT_FAILED')
    Assert-Equal $bigStatus 'PAYMENT_FAILED' 'over-threshold order reaches PAYMENT_FAILED'

    Write-Step 'Disruption: delete a pod under load'
    $loadJob = Start-Job -ScriptBlock {
        param([string]$Url)
        $requests = 0
        $failures = 0
        $deadline = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $deadline) {
            $requests++
            try {
                $response = Invoke-WebRequest -Uri "$Url/api/products" -TimeoutSec 3
                if ($response.StatusCode -ne 200) {
                    $failures++
                }
            }
            catch {
                $failures++
            }
        }
        [pscustomobject]@{ Requests = $requests; Failures = $failures }
    } -ArgumentList $BaseUrl

    Start-Sleep -Seconds 2
    $victims = Get-KubectlOutput @(
        'get', 'pods', '-n', $Namespace, '-l', 'app.kubernetes.io/name=order-service',
        '-o', 'jsonpath={.items[0].metadata.name}')
    $victim = ($victims -join '').Trim()
    if ($victim -eq '') {
        throw 'could not identify an order-service pod to delete.'
    }
    & kubectl delete pod $victim -n $Namespace | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "could not delete pod $victim."
    }
    Write-Pass "deleted $victim while requests were in flight"

    $outcome = Receive-Job -Job $loadJob -Wait | Select-Object -Last 1
    Assert-Equal $outcome.Failures 0 "no failed requests during deletion (of $($outcome.Requests))"

    $ready = Wait-ForReadyReplicas -Minimum 2 -TimeoutSeconds 120
    Assert-True ($ready -ge 2) "order-service returned to $ready Ready replicas"
}
catch {
    Write-Fail $_.Exception.Message
}
finally {
    if ($loadJob) {
        Remove-Job -Job $loadJob -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host "All $($script:Checks) checks passed." -ForegroundColor Green
    exit 0
}

Write-Host "$($script:Failures) of $($script:Checks) checks failed." -ForegroundColor Red
exit 1
