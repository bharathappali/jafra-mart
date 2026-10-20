#!/usr/bin/env bash
# Shared defaults and helpers. Source from the other scripts; do not execute.
set -euo pipefail

REGISTRY="${REGISTRY:-quay.io}"
REPO="${REPO:-bharathappali/jafra-mart}"
VERSION="${VERSION:-v1}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
CLUSTER="${CLUSTER:-jaframart}"
NAMESPACE="${NAMESPACE:-jafra-mart}"
export KIND_EXPERIMENTAL_PROVIDER="${KIND_EXPERIMENTAL_PROVIDER:-podman}"
COMPONENTS="${COMPONENTS:-catalog-search pricing inventory cart payments reports-worker recommendations orders storefront loadgen}"
DEPLOY_ORDER="postgres catalog-search pricing inventory cart payments reports-worker recommendations orders storefront loadgen"

if [[ "$(uname -s)" == "Darwin" ]]; then
  NATIVE_ARCH="$(podman info --format '{{.Host.Arch}}')"
else
  case "$(uname -m)" in
    x86_64) NATIVE_ARCH=amd64 ;;
    aarch64|arm64) NATIVE_ARCH=arm64 ;;
    *) NATIVE_ARCH="$(uname -m)" ;;
  esac
fi

if [[ -z "${ARTIFACTS:-}" ]]; then
  ARTIFACTS="run-artifacts/$(date -u +%Y%m%dT%H%M%SZ)"
fi
export ARTIFACTS

log() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    if [[ "$cmd" == "podman" ]] && command -v docker >/dev/null 2>&1; then
      die "docker is installed but podman is required; refusing to use docker"
    fi
    die "required command not found: $cmd"
  fi
}

repo_root() {
  cd "$(dirname "${BASH_SOURCE[1]}")/.." && pwd
}

dockerfile_for() {
  local c="$1"
  if [[ "$c" == "loadgen" ]]; then
    printf '%s\n' "loadgen/Dockerfile"
  else
    printf '%s\n' "services/${c}/Dockerfile"
  fi
}

manifest_for() {
  case "$1" in
    catalog-search) printf '%s\n' "manifests/10-catalog-search.yaml" ;;
    pricing) printf '%s\n' "manifests/11-pricing.yaml" ;;
    inventory) printf '%s\n' "manifests/12-inventory.yaml" ;;
    cart) printf '%s\n' "manifests/13-cart.yaml" ;;
    payments) printf '%s\n' "manifests/14-payments.yaml" ;;
    reports-worker) printf '%s\n' "manifests/15-reports-worker.yaml" ;;
    recommendations) printf '%s\n' "manifests/16-recommendations.yaml" ;;
    orders) printf '%s\n' "manifests/17-orders.yaml" ;;
    storefront) printf '%s\n' "manifests/18-storefront.yaml" ;;
    loadgen) printf '%s\n' "manifests/19-loadgen.yaml" ;;
    postgres) printf '%s\n' "manifests/05-postgres.yaml" ;;
    *) return 1 ;;
  esac
}

image_for() {
  printf '%s\n' "${REGISTRY}/${REPO}:${VERSION}-$1"
}

component_built() {
  [[ -f "$(dockerfile_for "$1")" ]]
}

port_for() {
  local c="$1" i=0 x
  for x in $COMPONENTS; do
    if [[ "$x" == "$c" ]]; then
      echo $((18000 + i))
      return 0
    fi
    i=$((i + 1))
  done
  return 1
}

cpu_millis() {
  local v="$1"
  if [[ "$v" == *m ]]; then
    echo "${v%m}"
  else
    awk -v v="$v" 'BEGIN { printf "%d", v * 1000 }'
  fi
}

mem_bytes() {
  local v="$1" n
  case "$v" in
    *Ki) n="${v%Ki}"; echo $((n * 1024)) ;;
    *Mi) n="${v%Mi}"; echo $((n * 1024 * 1024)) ;;
    *Gi) n="${v%Gi}"; echo $((n * 1024 * 1024 * 1024)) ;;
    *) echo "$v" ;;
  esac
}
