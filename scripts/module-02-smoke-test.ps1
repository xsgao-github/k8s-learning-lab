#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Smoke-tests the Module 2 slice against the local kind cluster.

.DESCRIPTION
    Verifies the catalog and cart API backed by PostgreSQL:
      1. unit tests for order-service
      2. image build and kind load
      3. deploy PostgreSQL and order-service
      4. catalog, cart add/increment/remove, 404 and persistence in PostgreSQL
      5. that POST /api/orders is wired (501 in Module 2; later modules replace it)

    Kafka is started because the current order-service binary requires a broker at
    startup. This script makes no Module 3 assertions.

    Exits 0 when every check passes, 1 otherwise.

.NOTES
    Requires Docker Desktop (Linux engine), kind, kubectl and Maven on PATH,
    plus the k8s-lab cluster from infra/kind-config.yaml with ingress-nginx
    installed. See docs/module-02-order-service.md.
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

function Assert-Amount {
    param([decimal]$Actual, [decimal]$Expected, [string]$What)
    if ($Actual -eq $Expected) {
        Write-Pass "$What = $Actual"
    }
    else {
        Write-Fail "$What expected '$Expected' but was '$Actual'"
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

function Wait-HttpOk {
    param([string]$Uri, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            return Invoke-RestMethod -Uri $Uri -TimeoutSec 5
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Uri did not become reachable."
}

function Set-CartItem {
    param([string]$UserId, [int]$ProductId, [int]$Quantity)
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/carts/$UserId/items" `
        -ContentType 'application/json' `
        -Body (@{ productId = $ProductId; quantity = $Quantity } | ConvertTo-Json)
}

function Get-Cart {
    param([string]$UserId)
    Invoke-RestMethod -Uri "$BaseUrl/api/carts/$UserId"
}

function Remove-CartItem {
    param([string]$UserId, [int]$ProductId)
    Invoke-RestMethod -Method Delete -Uri "$BaseUrl/api/carts/$UserId/items/$ProductId"
}

Push-Location $RepoRoot
try {
    Write-Step 'Prerequisites'
    foreach ($command in 'docker', 'kind', 'kubectl', 'mvn') {
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
    Write-Pass 'docker, kind, kubectl and mvn available; cluster running'

    Write-Step 'Unit tests'
    $testLog = Join-Path ([System.IO.Path]::GetTempPath()) "module-02-tests-$RunId.log"
    & mvn -q -pl order-service -am test *> $testLog
    if ($LASTEXITCODE -ne 0) {
        Get-Content $testLog -Tail 40 | ForEach-Object { Write-Host "         $_" }
        Remove-Item $testLog -Force -ErrorAction SilentlyContinue
        throw 'mvn test failed.'
    }
    Remove-Item $testLog -Force -ErrorAction SilentlyContinue
    Write-Pass 'mvn test'

    Write-Step 'Image'
    & docker build -q -f order-service/Dockerfile -t order-service:0.1.0 . | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'docker build failed for order-service.'
    }
    Write-Pass 'image order-service:0.1.0 built'
    & kind load docker-image order-service:0.1.0 --name $ClusterName 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kind load docker-image failed.'
    }
    Write-Pass 'image loaded into kind'

    Write-Step 'Deploy'
    & kubectl apply -f infra/k8s/namespace.yaml | Out-Null
    & kubectl delete configmap order-service-config -n $Namespace --ignore-not-found | Out-Null
    & kubectl apply -f infra/k8s/app-config.yaml -f infra/k8s/postgres.yaml -f infra/k8s/kafka.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for the platform failed.'
    }

    & kubectl rollout status statefulset/postgres -n $Namespace --timeout=240s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL did not become ready.'
    }
    Write-Pass 'PostgreSQL ready'

    # Required by the current order-service build, which wires Kafka at startup.
    & kubectl rollout status statefulset/kafka -n $Namespace --timeout=300s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Kafka did not become ready.'
    }
    Write-Pass 'Kafka ready'

    & kubectl apply -f infra/k8s/order-service.yaml -f infra/k8s/ingress.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for order-service failed.'
    }
    & kubectl rollout restart deployment/order-service -n $Namespace | Out-Null
    & kubectl rollout status deployment/order-service -n $Namespace --timeout=300s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'order-service did not become ready.'
    }
    Write-Pass 'order-service ready'

    Write-Step 'Catalog'
    $products = Wait-HttpOk -Uri "$BaseUrl/api/products"
    Assert-Equal (($products | Measure-Object).Count) 5 'seeded products through the Ingress'
    $espresso = $products | Where-Object { $_.id -eq 1 }
    Assert-Equal $espresso.name 'Espresso' 'product 1 name'
    Assert-Amount ([decimal]$espresso.price) 2.50 'product 1 price'

    Write-Step 'Cart'
    $user = "smoke02-$RunId"
    $cart = Get-Cart -UserId $user
    Assert-Equal (@($cart.items).Count) 0 'new cart is empty'
    Assert-Amount ([decimal]$cart.total) 0.00 'new cart total'

    $cart = Set-CartItem -UserId $user -ProductId 1 -Quantity 2
    Assert-Equal (@($cart.items).Count) 1 'one cart line after add'
    Assert-Equal ($cart.items[0].quantity) 2 'added quantity'
    Assert-Amount ([decimal]$cart.items[0].lineTotal) 5.00 'line total'
    Assert-Amount ([decimal]$cart.total) 5.00 'cart total'

    $cart = Set-CartItem -UserId $user -ProductId 1 -Quantity 1
    Assert-Equal (@($cart.items).Count) 1 'adding the same product increments the line'
    Assert-Equal ($cart.items[0].quantity) 3 'incremented quantity'
    Assert-Amount ([decimal]$cart.total) 7.50 'cart total after increment'

    $cart = Set-CartItem -UserId $user -ProductId 2 -Quantity 2
    Assert-Equal (@($cart.items).Count) 2 'second product added as a new line'
    Assert-Amount ([decimal]$cart.total) 15.00 'cart total with two products'

    $cart = Get-Cart -UserId $user
    Assert-Equal (@($cart.items).Count) 2 'cart survives a fresh read'
    Assert-Amount ([decimal]$cart.total) 15.00 'cart total survives a fresh read'

    Write-Step 'Persistence'
    $dbRow = ((Get-KubectlOutput @(
        'exec', '-n', $Namespace, 'postgres-0', '--',
        'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
        "select count(*) || ',' || coalesce(sum(quantity), 0) from cart_items where user_id = '$user'")) -join '').Trim()
    Assert-Equal $dbRow '2,5' 'cart lines and quantity persisted in PostgreSQL'

    Write-Step 'Removal and errors'
    $cart = Remove-CartItem -UserId $user -ProductId 1
    Assert-Equal (@($cart.items).Count) 1 'one line after removal'
    Assert-Amount ([decimal]$cart.total) 7.50 'cart total after removal'

    $cart = Remove-CartItem -UserId $user -ProductId 2
    Assert-Equal (@($cart.items).Count) 0 'cart empty after removing the last line'
    Assert-Amount ([decimal]$cart.total) 0.00 'final cart total'

    $unknown = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/carts/$user/items" `
        -ContentType 'application/json' `
        -Body (@{ productId = 999; quantity = 1 } | ConvertTo-Json) -SkipHttpErrorCheck
    Assert-Equal $unknown.StatusCode 404 'unknown product is rejected'

    $missing = Invoke-WebRequest -Method Delete -Uri "$BaseUrl/api/carts/$user/items/1" -SkipHttpErrorCheck
    Assert-Equal $missing.StatusCode 404 'removing a line that is not in the cart is rejected'

    Write-Step 'Ordering endpoint'
    $orders = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/orders" `
        -ContentType 'application/json' `
        -Body (@{ userId = $user } | ConvertTo-Json) -SkipHttpErrorCheck
    if ($orders.StatusCode -eq 501) {
        Write-Pass 'POST /api/orders is the Module 2 stub (501)'
    }
    elseif ($orders.StatusCode -in 200, 201, 409) {
        Write-Pass "POST /api/orders is served by a later module (HTTP $($orders.StatusCode))"
    }
    else {
        Write-Fail "POST /api/orders returned HTTP $($orders.StatusCode)"
    }
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
