[CmdletBinding()]
param(
    [string] $Registry = "suqihang",
    [string] $MiniPayTag = "0.1.0-k3s.1",
    [string] $YShopTag = "0.1.0-k3s.2-lite.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }

$names = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff", "merchant-web", "ops-web", "admin-web",
    "yshop-server", "yshop-food-h5", "yshop-admin-web"
)
$images = @($names | ForEach-Object {
    $tag = if ($_ -like "yshop-*") { $YShopTag } else { $MiniPayTag }
    "$Registry/$_`:$tag"
})
$nodes = @(& kubectl get nodes -o name)
Assert-Exit "Kubernetes node discovery"
if ($nodes.Count -lt 1) { throw "No Kubernetes node was found." }

foreach ($image in $images) {
    & docker image inspect $image --format "{{.Id}}" | Out-Null
    Assert-Exit "Local image inspection for $image"
}

foreach ($resource in $nodes) {
    if ($resource -notmatch '^node/([a-z0-9][a-z0-9.-]*)$') { throw "Unexpected node name: $resource" }
    $node = $Matches[1]
    & docker inspect $node --format "{{.Id}}" | Out-Null
    Assert-Exit "Docker container lookup for node $node"
    foreach ($image in $images) {
        # Tags are intentionally stable for the local learning environment. Always
        # import the current Docker image so a rebuilt tag cannot leave stale node
        # content behind.
        Write-Host "[RUN] Refresh $image -> $node"
        $safeCommand = "docker image save $image | docker exec -i $node ctr --namespace k8s.io images import -"
        & cmd.exe /d /s /c $safeCommand
        Assert-Exit "Image import for $image"
    }
}
Write-Host "[PASS] All 14 images are available to every discovered local node." -ForegroundColor Green
