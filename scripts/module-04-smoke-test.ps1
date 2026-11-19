#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Smoke-tests Module 4 against the local kind cluster.

.DESCRIPTION
    Verifies the browser client and the Ingress split:
      1. frontend image build and kind load
      2. deploy frontend-web and the updated Ingress
      3. the SPA is served at /, its JS bundle loads, and unknown paths fall back
         to index.html
      4. /api still routes to order-service
      5. the exact request sequence the UI performs reaches PRINTED

    Assumes the Module 3 stack is already deployed. Exits 0 when every check
    passes, 1 otherwise.

.NOTES
    Requires Docker Desktop (Linux engine), kind and kubectl on PATH, plus the
    k8s-lab cluster from infra/kind-config.yaml with ingress-nginx installed.
    See docs/module-04-frontend.md.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$Namespace = 'k8s-lab'
$BaseUrl = 'http://localhost'
$ClusterName = 'k8s-lab'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$RunId = Get-Date -Format 'yyyyMMddHHmmss'

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

function Wait-LogMatch {
    param([string]$Selector, [string]$Pattern, [int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $lines = & kubectl logs -n $Namespace -l $Selector --tail=1000 2>$null
        if (($lines -join "`n") -match $Pattern) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

Push-Location $RepoRoot
try {
    Write-Step 'Prerequisites'
    foreach ($command in 'docker', 'kind', 'kubectl') {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw "Required command '$command' was not found on PATH."
        }
    }
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker engine is not running.'
    }
    $clusters = & kind get clusters 2>$null
    if ($clusters -notcontains $ClusterName) {
        throw "kind cluster '$ClusterName' is not running. Run scripts/bootstrap-kind.ps1 first."
    }
    Write-Pass 'docker, kind and kubectl available; cluster running'

    Write-Step 'Image'
    & docker build -q -f frontend-web/Dockerfile -t frontend-web:0.1.0 . | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'docker build failed for frontend-web.'
    }
    Write-Pass 'image frontend-web:0.1.0 built'
    & kind load docker-image frontend-web:0.1.0 --name $ClusterName 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kind load docker-image failed.'
    }
    Write-Pass 'image loaded into kind'

    Write-Step 'Deploy'
    & kubectl apply -f infra/k8s/frontend-web.yaml -f infra/k8s/ingress.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for frontend-web failed.'
    }
    & kubectl rollout restart deployment/frontend-web -n $Namespace | Out-Null
    & kubectl rollout status deployment/frontend-web -n $Namespace --timeout=180s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'frontend-web did not become ready.'
    }
    $ready = @(& kubectl get pods -n $Namespace -l app.kubernetes.io/name=frontend-web --no-headers 2>$null | Where-Object { $_ -match '1/1\s+Running' })
    Assert-True ($ready.Count -ge 2) "at least two frontend replicas ready ($($ready.Count))"

    Write-Step 'UI'
    $page = Invoke-WebRequest -Uri "$BaseUrl/" -SkipHttpErrorCheck -TimeoutSec 10
    Assert-Equal $page.StatusCode 200 'GET / status'
    Assert-True (($page.Headers['Content-Type'] -join ';') -match 'text/html') 'GET / returns HTML'
    Assert-True ($page.Content -match '<title>Coffee Platform</title>') 'page title is Coffee Platform'
    Assert-True ($page.Content -match 'id="root"') 'React root element is present'

    $assetPath = [regex]::Match($page.Content, 'src="([^"]+\.js)"').Groups[1].Value
    Assert-True ($assetPath -like '/assets/*.js') 'index.html references a hashed bundle'
    $bundle = Invoke-WebRequest -Uri "$BaseUrl$assetPath" -SkipHttpErrorCheck -TimeoutSec 10
    Assert-Equal $bundle.StatusCode 200 'bundle status'
    Assert-True ($bundle.Content -match 'Checkout') 'bundle contains the app code'

    $deep = Invoke-WebRequest -Uri "$BaseUrl/orders/example" -SkipHttpErrorCheck -TimeoutSec 10
    Assert-Equal $deep.StatusCode 200 'unknown path falls back to index.html'
    Assert-True ($deep.Content -match 'id="root"') 'SPA fallback serves the app'

    Write-Step 'Ingress split'
    $products = Invoke-RestMethod -Uri "$BaseUrl/api/products" -TimeoutSec 10
    Assert-Equal (($products | Measure-Object).Count) 5 '/api still routes to order-service'

    Write-Step 'Order flow (the UI request sequence)'
    $user = "ui-smoke-$RunId"
    $cart = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/carts/$user/items" `
        -ContentType 'application/json' -Body '{"productId":3,"quantity":2}'
    Assert-Equal ([decimal]$cart.total) 6.50 'cart total'

    $order = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" `
        -ContentType 'application/json' -Body (@{ userId = $user } | ConvertTo-Json)
    Assert-Equal $order.status 'PENDING_PAYMENT' 'order accepted'

    $status = Wait-OrderStatus -OrderId $order.id -Expected @('PRINTED', 'PAYMENT_FAILED')
    Assert-Equal $status 'PRINTED' 'terminal status'

    $after = Invoke-RestMethod -Uri "$BaseUrl/api/carts/$user" -TimeoutSec 10
    Assert-Equal (@($after.items).Count) 0 'cart is emptied by checkout'

    $receipt = Wait-LogMatch -Selector 'app.kubernetes.io/name=print-service' `
        -Pattern "RECEIPT orderId=$([regex]::Escape($order.id))"
    Assert-True $receipt 'print-service logged a receipt for the order'
}
catch {
    Write-Fail $_.Exception.Message
}
finally {
    Pop-Location
}

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host "All $($script:Checks) checks passed." -ForegroundColor Green
    exit 0
}

Write-Host "$($script:Failures) of $($script:Checks) checks failed." -ForegroundColor Red
exit 1
