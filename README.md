# JafraMart

JafraMart is a small e-commerce suite that runs on a laptop kind cluster. On a deterministic 30-minute script it produces 13 JVM performance incidents covering Jafra subsystems 1–16. Each incident is application behavior (a flash sale, a cache without eviction, a slow payment gateway), ramps up, holds, and recovers while the app keeps serving traffic.

## Quick start

Host tools: JDK 25, the Maven Wrapper in this repo, podman 5, kind v0.33.0, kubectl 1.37, `git`, `gpg`, `curl`, `jq`, `openssl`, and bash 4+.

On macOS the podman machine must be rootful with at least 6 CPUs and 8 GiB:

```bash
podman machine set --rootful --cpus 6 --memory 8192
podman machine start
```

```bash
scripts/kind-up.sh
scripts/kind-load.sh
scripts/deploy.sh
scripts/smoke-test.sh
```

Open http://localhost:30080 . The conductor API is http://localhost:30081 .

Sign in as `admin` / `admin`, open Scenarios, and run the 30-minute script. The same run from the shell is `scripts/e2e-30min.sh`.

To use the public quay images instead of images built on this machine, skip `scripts/kind-load.sh`. `scripts/deploy.sh` sets `imagePullPolicy: IfNotPresent`, so the node pulls `quay.io/bharathappali/jafra-mart:v1-<component>`.

Public demo logins (not secrets):

| Username | Password | Role |
|---|---|---|
| `user` | `user` | shopper |
| `admin` | `admin` | admin |
| `customer001` … `customer200` | `customer` | shoppers used by the load generator |

The storefront is a demo BFF: session cookie `JSESSIONID` is `HttpOnly` with `SameSite=Lax`, and **CSRF protection is disabled** so the vanilla UI and loadgen can call JSON APIs with the session cookie alone.

## Contributing

Open pull requests to `main` in this order, and merge each with **Create a merge commit** (not squash, not rebase):

1. `00-foundation`
2. `01-catalog-search`
3. `02-pricing`
4. `03-inventory`
5. `04-cart`
6. `05-payments`
7. `06-reports-worker`
8. `07-recommendations`
9. `08-orders`
10. `09-storefront`
11. `10-loadgen`
12. `11-e2e-30min`

Never commit to or push `main` or `dev` from the implementation loop. Every commit is signed and signed-off (`git commit -s -S`).
