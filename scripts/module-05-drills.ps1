#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Interactive Module 5 drills: autoscaling and node drain.

.DESCRIPTION
    Walks through the two behaviours the smoke test does not automate. Each step
    explains what will happen, waits for Enter, runs the command, and shows the
    result, so nothing needs to be copied and pasted.

    The node drain is intentionally selective: PostgreSQL and Kafka use node-bound
    local-path volumes, so only order-service pods are evicted. Cleanup (delete the
    load deployment, uncordon the node) always runs, even if a step fails.

    This script is interactive and must not run in CI; use
    scripts/module-05-smoke-test.ps1 there.

.NOTES
    Requires kubectl on PATH and the k8s-lab cluster. Run it yourself in a terminal.
    See docs/module-05-resilience-autocaling.md.
#>
[CmdletBinding()]
param(
    [string]$Node = 'k8s-lab-worker2'
)

$ErrorActionPreference = 'Stop'

$Namespace = 'k8s-lab'
$loadDeployment = 'resilience-load'
$drained = $false

function Write-Step {
    param([string]$Title)
    Write-Host "`n== $Title" -ForegroundColor Cyan
}

function Confirm-Step {
    param([string]$Message)
    Write-Host "   Press Enter to $Message..." -ForegroundColor Yellow
    Read-Host | Out-Null
}

function Get-KubectlOutput {
    param([string[]]$Arguments)
    $output = & kubectl @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl $($Arguments[0]) failed with exit code $LASTEXITCODE"
    }
    return @($output)
}

function Get-DesiredReplicas {
    $value = (Get-KubectlOutput @(
        'get', 'hpa', 'order-service', '-n', $Namespace, '-o', 'jsonpath={.status.desiredReplicas}')) -join ''
    if ($value -eq '') {
        return 0
    }
    return [int]$value
}

function Remove-Load {
    & kubectl delete deployment $loadDeployment -n $Namespace --ignore-not-found 2>$null | Out-Null
}

function Uncordon-Node {
    $unschedulable = (Get-KubectlOutput @(
        'get', 'node', $Node, '-o', 'jsonpath={.spec.unschedulable}')) -join ''
    if ($unschedulable -eq 'true') {
        & kubectl uncordon $Node | Out-Null
        Write-Host "   Node $Node uncordoned." -ForegroundColor DarkGray
    }
}

try {
    Write-Step 'Preflight'
    if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
        throw 'kubectl was not found on PATH.'
    }

    $available = (Get-KubectlOutput @(
        'get', 'apiservice', 'v1beta1.metrics.k8s.io',
        '-o', 'jsonpath={.status.conditions[?(@.type=="Available")].status}')) -join ''
    if ($available -ne 'True') {
        throw 'The metrics API is not available. Run scripts/bootstrap-kind.ps1 first.'
    }
    Write-Host '   metrics-server: available'

    & kubectl get hpa order-service -n $Namespace | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The order-service HPA is missing. Apply infra/k8s/hpa.yaml first.'
    }
    & kubectl get pdb order-service -n $Namespace | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The order-service PDB is missing. Apply infra/k8s/pdb.yaml first.'
    }

    $nodeLabels = ''
    try {
        $nodeLabels = (Get-KubectlOutput @(
            'get', 'node', $Node, '-o', 'jsonpath={.metadata.labels.ingress-ready}')) -join ''
    }
    catch {
        throw "Node $Node was not found."
    }
    if ($nodeLabels -eq 'true') {
        throw "Node $Node hosts the Ingress controller. Run with -Node <the other worker>."
    }
    Write-Host "   drain target: $Node (no ingress-ready label)"

    Write-Step 'Step 1/5 - start the load'
    Write-Host '   4 busybox pods will loop GET /api/products against the service.'
    Confirm-Step 'create the load Deployment'
    $manifest = @'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resilience-load
  namespace: k8s-lab
spec:
  replicas: 4
  selector:
    matchLabels:
      app: resilience-load
  template:
    metadata:
      labels:
        app: resilience-load
    spec:
      containers:
        - name: load
          image: busybox:1.36
          imagePullPolicy: IfNotPresent
          command:
            - sh
            - -c
            - while true; do wget -q -O- http://order-service:8081/api/products >/dev/null; done
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
'@
    $manifest | kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create the load Deployment.'
    }

    Write-Step 'Step 2/5 - watch the HPA scale up'
    Write-Host '   Polling every 15 s for up to 5 minutes. Metrics take 30-60 s to reflect the load.'
    Confirm-Step 'start watching'
    $deadline = (Get-Date).AddMinutes(5)
    $replicas = 0
    do {
        $snapshot = (& kubectl get hpa order-service -n $Namespace --no-headers 2>$null) -join ''
        $replicas = Get-DesiredReplicas
        Write-Host ("   {0:HH:mm:ss}  desired={1}  {2}" -f (Get-Date), $replicas, $snapshot.Trim())
        if ($replicas -gt 2) {
            break
        }
        Start-Sleep -Seconds 15
    } while ((Get-Date) -lt $deadline)

    if ($replicas -gt 2) {
        Write-Host "   HPA scaled to $replicas replicas." -ForegroundColor Green
    }
    else {
        Write-Host '   HPA did not scale above 2. Increase the load replicas and watch again.' -ForegroundColor Yellow
    }

    Write-Step 'Step 3/5 - stop the load and watch it settle'
    Confirm-Step 'delete the load Deployment'
    Remove-Load
    Write-Host '   Scale-down has a 60 s stabilization window; polling for up to 3 minutes.'
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $replicas = Get-DesiredReplicas
        Write-Host ("   {0:HH:mm:ss}  desired={1}" -f (Get-Date), $replicas)
        if ($replicas -le 2) {
            break
        }
        Start-Sleep -Seconds 15
    } while ((Get-Date) -lt $deadline)

    Write-Step 'Step 4/5 - drain the node (order-service pods only)'
    Write-Host "   Draining $Node with --pod-selector=app.kubernetes.io/name=order-service."
    Write-Host '   PostgreSQL and Kafka are left alone because their volumes are node-bound.'
    Write-Host ''
    & kubectl get pdb order-service -n $Namespace
    Write-Host ''
    Confirm-Step "drain $Node"
    $drained = $true
    & kubectl drain $Node --ignore-daemonsets --delete-emptydir-data `
        --pod-selector='app.kubernetes.io/name=order-service' --timeout=180s
    if ($LASTEXITCODE -ne 0) {
        Write-Host '   Drain did not complete. Inspecting pods before cleanup.' -ForegroundColor Yellow
    }

    Write-Host ''
    & kubectl get pods -n $Namespace -l app.kubernetes.io/name=order-service -o wide
    $ready = @(& kubectl get pods -n $Namespace -l app.kubernetes.io/name=order-service --no-headers 2>$null |
        Where-Object { $_ -match '1/1\s+Running' })
    Write-Host ''
    if ($ready.Count -ge 1) {
        Write-Host "   $($ready.Count) order-service replica(s) still Ready; the PDB held." -ForegroundColor Green
    }
    else {
        Write-Host '   No order-service replica is Ready. Inspect the pods above.' -ForegroundColor Red
    }

    Write-Step 'Step 5/5 - restore the node'
    Confirm-Step "uncordon $Node"
    & kubectl uncordon $Node | Out-Null
    $drained = $false
    Write-Host "   $Node is schedulable again." -ForegroundColor Green
}
finally {
    Remove-Load
    if ($drained) {
        Uncordon-Node
    }
    Write-Host ''
    Write-Host 'Cleanup complete: load Deployment removed, node restored.' -ForegroundColor DarkGray
}
