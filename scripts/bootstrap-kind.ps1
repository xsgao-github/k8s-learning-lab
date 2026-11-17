#Requires -Version 5.1
<#
.SYNOPSIS
    Bootstraps the k8s-lab kind cluster and the ingress-nginx controller on Windows.

.DESCRIPTION
    Verifies Docker Desktop (Linux engine), installs kind with winget if needed,
    creates the cluster from infra/kind-config.yaml, installs ingress-nginx, and
    waits for the controller to become ready.

.PARAMETER ClusterName
    kind cluster name. Defaults to k8s-lab.

.PARAMETER Recreate
    Delete an existing cluster with the same name before creating it again.

.EXAMPLE
    .\scripts\bootstrap-kind.ps1

.EXAMPLE
    .\scripts\bootstrap-kind.ps1 -Recreate
#>
[CmdletBinding()]
param(
    [string]$ClusterName = 'k8s-lab',
    [switch]$Recreate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$kindConfig = Join-Path $repoRoot 'infra\kind-config.yaml'
$ingressManifest = 'https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml'

function Update-SessionPath {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
}

function Test-CommandExists([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

Write-Host '==> Checking Docker Desktop...'
if (-not (Test-CommandExists 'docker')) {
    throw 'docker was not found. Install Docker Desktop and start its Linux engine.'
}
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not running or the Linux engine is unavailable. Start Docker Desktop and rerun.'
}

if (-not (Test-Path $kindConfig)) {
    throw "Missing kind config: $kindConfig"
}

if (-not (Test-CommandExists 'kind')) {
    if (-not (Test-CommandExists 'winget')) {
        throw 'kind is missing and winget is unavailable. Install kind manually: https://kind.sigs.k8s.io/docs/user/quick-start/#installation'
    }
    Write-Host '==> Installing kind with winget...'
    winget install --id Kubernetes.kind --exact --source winget --silent `
        --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) {
        throw 'winget could not install kind.'
    }
    Update-SessionPath
}
if (-not (Test-CommandExists 'kind')) {
    throw 'kind was installed but is not on PATH. Open a new terminal and rerun this script.'
}

if (-not (Test-CommandExists 'kubectl')) {
    $dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
    if (Test-Path (Join-Path $dockerBin 'kubectl.exe')) {
        $env:Path = "$env:Path;$dockerBin"
    }
}
if (-not (Test-CommandExists 'kubectl')) {
    throw 'kubectl was not found. Install it or add Docker Desktop''s bin directory to PATH.'
}

$clusters = @(kind get clusters 2>$null)
if ($clusters -contains $ClusterName) {
    if ($Recreate) {
        Write-Host "==> Deleting existing cluster '$ClusterName'..."
        kind delete cluster --name $ClusterName
        if ($LASTEXITCODE -ne 0) { throw "Failed to delete cluster '$ClusterName'." }
    }
    else {
        Write-Host "==> Cluster '$ClusterName' already exists; reusing it (use -Recreate to start over)."
    }
}

$clusters = @(kind get clusters 2>$null)
if (-not ($clusters -contains $ClusterName)) {
    Write-Host "==> Creating cluster '$ClusterName'..."
    kind create cluster --name $ClusterName --config $kindConfig --wait 120s
    if ($LASTEXITCODE -ne 0) { throw "Failed to create cluster '$ClusterName'." }
}

Write-Host '==> Installing ingress-nginx (kind provider)...'
kubectl apply -f $ingressManifest
if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the ingress-nginx manifest.' }

Write-Host '==> Pinning the ingress controller to the ingress-ready node...'
kubectl patch deployment ingress-nginx-controller -n ingress-nginx --type=merge `
    -p '{"spec":{"template":{"spec":{"nodeSelector":{"ingress-ready":"true"}}}}}'
if ($LASTEXITCODE -ne 0) { throw 'Failed to pin the ingress controller to the ingress-ready node.' }

Write-Host '==> Waiting for the ingress controller to become ready...'
kubectl wait --namespace ingress-nginx --for=condition=ready pod `
    --selector=app.kubernetes.io/component=controller --timeout=180s
if ($LASTEXITCODE -ne 0) { throw 'The ingress controller did not become ready.' }

Write-Host '==> Verifying cluster...'
kubectl get nodes
kubectl get pods -n ingress-nginx

Write-Host ""
Write-Host "Module 0 bootstrap complete. kubectl context: kind-$ClusterName"
