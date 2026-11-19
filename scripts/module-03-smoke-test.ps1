#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Smoke-tests Module 3 against the local kind cluster.

.DESCRIPTION
    Runs the module's smoke test as one idempotent sequence:
      1. unit tests for order-service, payment-service and print-service
      2. image build and kind load
      3. deploy Kafka, PostgreSQL and the three services
      4. topic and partition checks
      5. a normal order (expects PRINTED), an order over 1000 (expects
         PAYMENT_FAILED), and an empty cart (expects 409)
      6. receipt, message-key and outbox-drain evidence

    Exits 0 when every check passes, 1 otherwise.

.NOTES
    Requires Docker Desktop (Linux engine), kind, kubectl and Maven on PATH,
    plus the k8s-lab cluster from infra/kind-config.yaml with ingress-nginx
    installed. See docs/module-03-payment-print-service.md.
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

function Assert-Match {
    param([string]$Actual, [string]$Pattern, [string]$What)
    if ($Actual -match $Pattern) {
        Write-Pass $What
    }
    else {
        Write-Fail "$What (pattern '$Pattern' was not found)"
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

function New-CartItem {
    param([string]$UserId, [int]$ProductId, [int]$Quantity)
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/carts/$UserId/items" `
        -ContentType 'application/json' `
        -Body (@{ productId = $ProductId; quantity = $Quantity } | ConvertTo-Json) | Out-Null
}

function Wait-LogMatch {
    param([string]$Selector, [string]$Pattern, [int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        # --since combined with a label selector returns nothing on this kubectl;
        # --tail is reliable and the caller reads immediately after the event.
        $lines = & kubectl logs -n $Namespace -l $Selector --tail=1000 2>$null
        if (($lines -join "`n") -match $Pattern) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Wait-NoPods {
    param([string]$Selector, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $pods = & kubectl get pods -n $Namespace -l $Selector --no-headers 2>$null
        if (-not $pods) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function New-Order {
    param([string]$UserId)
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" `
        -ContentType 'application/json' `
        -Body (@{ userId = $UserId } | ConvertTo-Json)
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
    $testLog = Join-Path ([System.IO.Path]::GetTempPath()) "module-03-tests-$RunId.log"
    & mvn -q -pl order-service,payment-service,print-service -am test *> $testLog
    if ($LASTEXITCODE -ne 0) {
        Get-Content $testLog -Tail 40 | ForEach-Object { Write-Host "         $_" }
        Remove-Item $testLog -Force -ErrorAction SilentlyContinue
        throw 'mvn test failed.'
    }
    Remove-Item $testLog -Force -ErrorAction SilentlyContinue
    Write-Pass 'mvn test'

    Write-Step 'Images'
    foreach ($service in 'order-service', 'payment-service', 'print-service') {
        & docker build -q -f "$service/Dockerfile" -t "${service}:0.1.0" . | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker build failed for $service."
        }
        Write-Pass "image ${service}:0.1.0 built"
    }
    & kind load docker-image order-service:0.1.0 payment-service:0.1.0 print-service:0.1.0 --name $ClusterName 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kind load docker-image failed.'
    }
    Write-Pass 'images loaded into kind'

    Write-Step 'Deploy'
    & kubectl apply -f infra/k8s/namespace.yaml | Out-Null
    & kubectl delete configmap app-config -n $Namespace --ignore-not-found | Out-Null
    & kubectl apply -f infra/k8s/app-config.yaml -f infra/k8s/postgres.yaml -f infra/k8s/kafka.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for the platform failed.'
    }

    & kubectl rollout status statefulset/kafka -n $Namespace --timeout=300s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Kafka did not become ready.'
    }
    Write-Pass 'Kafka ready'

    & kubectl apply -f infra/k8s/order-service.yaml -f infra/k8s/payment-service.yaml -f infra/k8s/print-service.yaml -f infra/k8s/ingress.yaml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl apply for the services failed.'
    }
    # imagePullPolicy: IfNotPresent plus a reused tag means an unchanged template
    # keeps the old image running; restart so the freshly built images are used.
    & kubectl rollout restart deployment/order-service deployment/payment-service deployment/print-service -n $Namespace | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl rollout restart failed.'
    }
    foreach ($deployment in 'order-service', 'payment-service', 'print-service') {
        & kubectl rollout status "deployment/$deployment" -n $Namespace --timeout=300s | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "$deployment did not become ready."
        }
        Write-Pass "$deployment ready"
    }

    Write-Step 'Ingress'
    try {
        # No @() here: in a fresh pwsh, Invoke-RestMethod already returns an Object[],
        # and wrapping it again nests the array into a single element.
        $products = Invoke-RestMethod -Uri "$BaseUrl/api/products" -TimeoutSec 10
    }
    catch {
        throw "http://localhost/api/products is not reachable. Is ingress-nginx installed? ($($_.Exception.Message))"
    }
    Assert-Equal (($products | Measure-Object).Count) 5 'catalog products through the Ingress'

    Write-Step 'Kafka topics'
    $topics = Get-KubectlOutput @(
        'exec', '-n', $Namespace, 'kafka-0', '--',
        '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9094', '--list')
    foreach ($topic in 'order.placed', 'payment.processed', 'print.completed') {
        Assert-Equal ([bool]($topics -contains $topic)) $true "topic $topic exists"
    }
    $describe = Get-KubectlOutput @(
        'exec', '-n', $Namespace, 'kafka-0', '--',
        '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9094', '--describe', '--topic', 'order.placed')
    Assert-Match ($describe -join "`n") 'PartitionCount:\s*3' 'order.placed has 3 partitions'

    Write-Step 'Normal order'
    $okUser = "verify-ok-$RunId"
    New-CartItem -UserId $okUser -ProductId 1 -Quantity 2
    $okOrder = New-Order -UserId $okUser
    Assert-Equal $okOrder.status 'PENDING_PAYMENT' 'initial status'
    $okStatus = Wait-OrderStatus -OrderId $okOrder.id -Expected @('PRINTED', 'PAYMENT_FAILED')
    Assert-Equal $okStatus 'PRINTED' 'terminal status'
    Assert-Amount ([decimal]$okOrder.totalAmount) 5.00 'order total'

    Write-Step 'Order over the decline threshold'
    $bigUser = "verify-declined-$RunId"
    New-CartItem -UserId $bigUser -ProductId 1 -Quantity 401
    $bigOrder = New-Order -UserId $bigUser
    Assert-Amount ([decimal]$bigOrder.totalAmount) 1002.50 'order total'
    $bigStatus = Wait-OrderStatus -OrderId $bigOrder.id -Expected @('PRINTED', 'PAYMENT_FAILED')
    Assert-Equal $bigStatus 'PAYMENT_FAILED' 'terminal status'
    $printLogs = & kubectl logs -n $Namespace -l app.kubernetes.io/name=print-service --tail=1000 2>$null
    if (($printLogs -join "`n") -notmatch [regex]::Escape($bigOrder.id)) {
        Write-Pass 'no receipt for the declined order'
    }
    else {
        Write-Fail 'no receipt for the declined order'
    }
    if (Wait-LogMatch -Selector 'app.kubernetes.io/name=payment-service' `
            -Pattern "orderId=$([regex]::Escape($bigOrder.id))\s+totalAmount=1002\.5") {
        Write-Pass 'payment-service declined the over-threshold order'
    }
    else {
        Write-Fail 'payment-service declined the over-threshold order'
    }

    Write-Step 'Empty cart'
    $emptyUser = "verify-empty-$RunId"
    $empty = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/orders" -ContentType 'application/json' `
        -Body (@{ userId = $emptyUser } | ConvertTo-Json) -SkipHttpErrorCheck
    Assert-Equal $empty.StatusCode 409 'HTTP status'

    Write-Step 'Smoke: receipt arrives before payment'
    $raceUser = "verify-race-$RunId"
    $raceOrder = $null
    $paymentScaledDown = $false
    try {
        & kubectl scale deployment/payment-service -n $Namespace --replicas=0 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'could not scale payment-service down.'
        }
        $paymentScaledDown = $true
        if (-not (Wait-NoPods -Selector 'app.kubernetes.io/name=payment-service' -TimeoutSeconds 60)) {
            throw 'payment-service pods did not terminate.'
        }
        Write-Pass 'payment-service stopped'

        New-CartItem -UserId $raceUser -ProductId 2 -Quantity 1
        $raceOrder = New-Order -UserId $raceUser
        Assert-Equal $raceOrder.status 'PENDING_PAYMENT' 'order accepted while payment is down'

        $deadline = (Get-Date).AddSeconds(30)
        do {
            $unsent = ((Get-KubectlOutput @(
                'exec', '-n', $Namespace, 'postgres-0', '--',
                'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
                'select count(*) from outbox where sent_at is null')) -join '').Trim()
            if ($unsent -eq '0') {
                break
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        Assert-Equal $unsent '0' 'order.placed published'

        # Inject the receipt first. It must be parked, never applied.
        $payload = @{
            eventId     = [guid]::NewGuid().ToString()
            orderId     = $raceOrder.id
            userId      = $raceUser
            totalAmount = [decimal]$raceOrder.totalAmount
            status      = 'PRINTED'
            timestamp   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        } | ConvertTo-Json -Compress
        "$($raceOrder.id):$payload" | kubectl exec -i -n $Namespace kafka-0 -- `
            /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9094 `
            --topic print.completed --property parse.key=true --property key.separator=: 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'could not inject print.completed.'
        }

        $pending = $null
        $status = $null
        $deferred = $false
        $deadline = (Get-Date).AddSeconds(30)
        do {
            $pending = ((Get-KubectlOutput @(
                'exec', '-n', $Namespace, 'postgres-0', '--',
                'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
                "select count(*) from pending_events where order_id='$($raceOrder.id)'")) -join '').Trim()
            $status = ((Get-KubectlOutput @(
                'exec', '-n', $Namespace, 'postgres-0', '--',
                'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
                "select status from orders where id='$($raceOrder.id)'")) -join '').Trim()
            if ($pending -eq '1' -and $status -eq 'PENDING_PAYMENT') {
                $deferred = $true
                break
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        Assert-Equal $deferred $true 'receipt deferred instead of applied'
        Assert-Equal $status 'PENDING_PAYMENT' 'order stays unpaid'
    }
    finally {
        if ($paymentScaledDown) {
            & kubectl scale deployment/payment-service -n $Namespace --replicas=1 | Out-Null
            & kubectl rollout status deployment/payment-service -n $Namespace --timeout=240s | Out-Null
        }
    }
    Write-Pass 'payment-service restored'

    $raceStatus = Wait-OrderStatus -OrderId $raceOrder.id -Expected @('PRINTED', 'PAYMENT_FAILED') -TimeoutSeconds 90
    Assert-Equal $raceStatus 'PRINTED' 'deferred receipt applied after payment'
    $racePending = ((Get-KubectlOutput @(
        'exec', '-n', $Namespace, 'postgres-0', '--',
        'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
        "select count(*) from pending_events where order_id='$($raceOrder.id)'")) -join '').Trim()
    Assert-Equal $racePending '0' 'deferred row removed'

    Write-Step 'Evidence'
    if (Wait-LogMatch -Selector 'app.kubernetes.io/name=print-service' `
            -Pattern "RECEIPT orderId=$([regex]::Escape($okOrder.id))") {
        Write-Pass 'print-service logged a receipt for the successful order'
    }
    else {
        Write-Fail 'print-service logged a receipt for the successful order'
    }

    $sample = Get-KubectlOutput @(
        'exec', '-n', $Namespace, 'kafka-0', '--',
        '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'localhost:9094',
        '--topic', 'payment.processed', '--from-beginning', '--max-messages', '1',
        '--timeout-ms', '15000', '--property', 'print.key=true', '--property', 'key.separator=:')
    Assert-Match ($sample -join "`n") '^[0-9a-fA-F-]{36}:' 'payment.processed messages are keyed by orderId'

    $unsent = $null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $unsent = ((Get-KubectlOutput @(
            'exec', '-n', $Namespace, 'postgres-0', '--',
            'psql', '-U', 'coffee', '-d', 'coffee', '-tAc',
            'select count(*) from outbox where sent_at is null')) -join '').Trim()
        if ($unsent -eq '0') {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    Assert-Equal $unsent '0' 'unsent outbox rows'
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
