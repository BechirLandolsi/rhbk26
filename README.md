# RHBK26 on OpenShift

This repository contains **Red Hat Build of Keycloak (RHBK) 26.x** deployment examples for OpenShift: plain Kubernetes manifests, a Helm chart that renders the `Keycloak` custom resource, **Crunchy Postgres** for persistence, **monitoring** (User Workload Monitoring, `ServiceMonitor`, Grafana Operator), **distributed tracing** (Tempo Operator + optional MinIO backend), and **Argo CD** `Application` samples.

Use it as a blueprint: **replace cluster URLs, namespaces, secrets, and operator channels** to match your environment and compliance rules.

---

## Table of contents

1. [What is in this repo](#what-is-in-this-repo)
2. [Prerequisites](#prerequisites)
3. [Recommended deployment order](#recommended-deployment-order)
4. [1. Crunchy Postgres (install first)](#1-crunchy-postgres-install-first)
5. [2. RHBK Operator (OLM)](#2-rhbk-operator-olm)
6. [3. Deploy Keycloak](#3-deploy-keycloak)
   - [Option A: Plain manifests](#option-a-plain-manifests)
   - [Option B: Helm chart](#option-b-helm-chart)
7. [Routes, secrets, and scaling](#routes-secrets-and-scaling)
8. [Monitoring: Prometheus (UWM), ServiceMonitor, Grafana](#monitoring-prometheus-uwm-servicemonitor-grafana)
9. [Tracing: MinIO, Tempo, Keycloak OTLP](#tracing-minio-tempo-keycloak-otlp)
10. [Argo CD integration](#argo-cd-integration)
11. [Optional components](#optional-components)
12. [Security and production notes](#security-and-production-notes)

---

## What is in this repo

| Area | Path | Purpose |
|------|------|---------|
| Keycloak CR + helpers | `rhbk26/` | Operator `Keycloak` CR, HPA, `ServiceMonitor`, routes, DB/admin secrets, FranceConnect / custom provider ConfigMaps, subscription example |
| Helm chart | `rhbk26/GitOps/rhbk-helm/` | Same as manifests, parameterized via `values.yaml` (includes optional `ServiceMonitor`, HPA, routes) |
| RHBK Operator (GitOps) | `rhbk26/GitOps/rhbk-operator/base/` | `OperatorGroup`, `Subscription` (manual approval) + install-plan approver job |
| Crunchy | `crunchy/` | Operator subscription + `PostgresCluster` + user secret |
| Grafana | `Grafana/` | UWM enablement, Grafana Operator subscription, `Grafana` CR, datasource, dashboards, RBAC |
| Tempo | `tempo/` | Tempo Operator subscription (namespace `openshift-tempo-operator`), `TempoStack`, MinIO S3 secret |
| MinIO (demo backend) | `minio/` | Deployment + PVC + Routes for S3-compatible storage used by Tempo |
| Argo CD apps | `argo/` | Example `Application` manifests pointing at this repo |
| Custom image | `rhbk26/Containerfile` | Multi-stage build baking providers + build-time metrics/health/tracing flags |
| Password expiry SPI | `password-expiry-idm/` | Example Java extension JAR for Keycloak |

---

## Prerequisites

- OpenShift 4.x with **cluster admin** for operators, monitoring, and CRDs.
- `oc` / `kubectl` configured.
- Access to **Red Hat Operator catalogs** (`redhat-operators`, `certified-operators`, `community-operators` as needed).
- For RHBK: entitlement to **Red Hat Build of Keycloak** images (e.g. `registry.redhat.io/rhbk/...`) where applicable.
- A **namespace** for Keycloak (examples use `keycloak`).

---

## Recommended deployment order

A typical end-to-end stack:

1. **Namespaces** (e.g. `keycloak`, `tempo`, `minio`, `openshift-user-workload-monitoring` is usually pre-existing).
2. **Crunchy Postgres Operator** + **PostgresCluster** + DB user secret.
3. **RHBK Operator** subscription (and approve InstallPlan if manual).
4. **Secrets** Keycloak needs (`keycloak-db-secret`, bootstrap admin secret, pull secret if using a private image).
5. **Keycloak** CR (manifests or Helm)—**one** method per cluster instance.
6. **User Workload Monitoring** + **ServiceMonitor** (scrape Keycloak management metrics).
7. **Grafana Operator** + **Grafana** + datasource + dashboards (optional but documented here).
8. **MinIO** (if you need an in-cluster S3-compatible store for Tempo).
9. **Tempo Operator** + **TempoStack** + secret pointing at object storage.
10. Enable **tracing** on Keycloak (`tracing-enabled`, `tracing-endpoint`) aligned with your Tempo distributor URL.
11. **Argo CD** `Application` resources to GitOps the above (optional).

---

## 1. Crunchy Postgres (install first)

Install the **Crunchy Postgres for Kubernetes** operator and database **before** the Keycloak instance connects.

### Files

- `crunchy/crunchy-subscription.yaml` — `OperatorGroup` + `Subscription` in namespace `keycloak` (channel `v5`, `certified-operators`).
- `crunchy/pg-cluster.yaml` — `PostgresCluster` named `postgresdb` with database `keycloak` and user `keycloak`.
- `crunchy/secret-password-db.yaml` — example secret for the `keycloak` user password (demo values; replace in production).

### Apply (CLI example)

```bash
oc create namespace keycloak --dry-run=client -o yaml | oc apply -f -
oc apply -f crunchy/crunchy-subscription.yaml
# Wait until the operator CSV is Succeeded, then:
oc apply -f crunchy/secret-password-db.yaml
oc apply -f crunchy/pg-cluster.yaml
```

The Keycloak CR in this repo expects:

- **Host:** `postgresdb-primary.keycloak.svc.cluster.local`
- **Database:** `keycloak`
- **Credentials:** Kubernetes secret `keycloak-db-secret` with keys `username` and `password` (see `rhbk26/keycloak-db-secret.yaml`).

**Warning:** The sample `pg-cluster.yaml` relaxes `pg_hba` for demonstration only. Tighten network and authentication rules for production.

---

## 2. RHBK Operator (OLM)

Keycloak is deployed with the **`rhbk-operator`** from `redhat-operators` (CRD group `k8s.keycloak.org`).

### Option A: Subscription only (example in repo root)

`rhbk26/kc-subscription.yaml` — `OperatorGroup` + `Subscription` with **Automatic** install and channel `stable-v26.2` (adjust channel/CSV to your target version).

### Option B: GitOps-friendly manual approval

`rhbk26/GitOps/rhbk-operator/base/` bundles:

- `subscription.yaml` — `installPlanApproval: Manual`, channel `stable-v26.4` (update to match your target).
- `operator-group.yaml`
- Reference to `installplan-approver` so pending InstallPlans can be approved automatically in CI/GitOps (see `rhbk26/GitOps/installplan-approver/README.md`).

Apply the kustomize base:

```bash
oc apply -k rhbk26/GitOps/rhbk-operator/base
```

Wait until the **RHBK operator** CSV is **Succeeded** before applying the `Keycloak` CR.

---

## 3. Deploy Keycloak

Use **either** plain manifests **or** Helm—not two competing controllers for the same logical instance without a clear migration plan.

### Option A: Plain manifests

Relevant files under `rhbk26/`:

| File | Role |
|------|------|
| `keycloak.yaml` | Main `Keycloak` CR (`k8s.keycloak.org/v2alpha1`) |
| `keycloak-db-secret.yaml` | DB credentials consumed by the CR |
| `keycloak-admin-secret.yaml` | Bootstrap admin (referenced by `spec.bootstrapAdmin`) |
| `truststore-secret.yaml` | Optional TLS trust (if used) |
| `route.yaml`, `keycloak-admin-route.yaml`, `keycloak-management-route.yaml` | OpenShift `Route` objects when `spec.ingress.enabled: false` |
| `service-monitor.yaml` | `ServiceMonitor` for management port metrics |
| `keycloak-hpa.yaml` | HPA targeting the `Keycloak` CR (RHBK 26.2+) |
| `kc-subscription.yaml` | Operator subscription (if not installed via GitOps base) |

Edit **`spec.hostname.hostname`**, **`spec.hostname.admin`**, **`imagePullSecrets`**, and **`image`** (if using a custom build) to match your cluster.

Apply:

```bash
oc apply -f rhbk26/keycloak-db-secret.yaml
# initial-admin-secret, pull secrets, ConfigMaps for providers as needed
oc apply -f rhbk26/keycloak.yaml
oc apply -f rhbk26/service-monitor.yaml
oc apply -f rhbk26/keycloak-hpa.yaml   # optional
# Routes if you manage them outside the CR
oc apply -f rhbk26/route.yaml
oc apply -f rhbk26/keycloak-admin-route.yaml
oc apply -f rhbk26/keycloak-management-route.yaml
```

### Option B: Helm chart

Chart: `rhbk26/GitOps/rhbk-helm/`

```bash
helm template keycloak rhbk26/GitOps/rhbk-helm -f rhbk26/GitOps/rhbk-helm/values.yaml | oc apply -f -
# or
helm install keycloak rhbk26/GitOps/rhbk-helm -n keycloak --create-namespace -f rhbk26/GitOps/rhbk-helm/values.yaml
```

Key knobs in `values.yaml`:

- `global.namespace`, `keycloak.hostname.*`, `keycloak.db.*`
- `keycloak.serviceMonitor.enabled` — chart can render `ServiceMonitor` with path derived from `http-management-relative-path` (see `templates/service-monitor.yaml`).
- `keycloak.autoscaling.enabled` — HPA on the `Keycloak` CR
- `keycloak.unsupported` / `providerConfigMaps` — optional JAR providers (align with your ConfigMaps)

---

## Routes, secrets, and scaling

- **Ingress:** In samples, `spec.ingress.enabled` is `false`; exposure is via **OpenShift Routes** manifests.
- **Management port:** `http-management-relative-path` is set to `/management` in examples so health/metrics/tracing endpoints follow that path segment.
- **HPA:** `rhbk26/keycloak-hpa.yaml` scales the `Keycloak` CR by CPU/memory. Ensure replica bounds and resources match your SLA.
- **Custom image:** `rhbk26/Containerfile` shows a pattern: build stage sets `KC_METRICS_ENABLED`, `KC_HEALTH_ENABLED`, `KC_TRACING_ENABLED`, etc., then runtime image is produced for use in `spec.image`.

---

## Monitoring: Prometheus (UWM), ServiceMonitor, Grafana

### Enable User Workload Monitoring

User-defined metrics (including `ServiceMonitor` in user namespaces) are scraped when **user workload monitoring** is enabled.

Apply:

```bash
oc apply -f Grafana/uwm-configmap.yaml
```

This sets `enableUserWorkload: true` in `openshift-monitoring` (`cluster-monitoring-config` ConfigMap). On OpenShift 4, follow current product docs if the ConfigMap name or structure differs in your version.

### ServiceMonitor

- Standalone example: `rhbk26/service-monitor.yaml` — selects `app: keycloak`, scrapes port `management`, path `/auth/management/metrics` (path must match your Keycloak **`http-relative-path`** and **`http-management-relative-path`**; if you use `/` and `/management`, align to `/management/metrics` or your actual metrics path).
- Helm: set `keycloak.serviceMonitor.enabled: true` in `rhbk26/GitOps/rhbk-helm/values.yaml`; the chart computes the metrics path from `http-management-relative-path`.

Ensure the Keycloak CR has **`metrics-enabled`** and **`health-enabled`** in `additionalOptions` (see `rhbk26/keycloak.yaml`).

### Grafana Operator + Grafana + dashboards

Files under `Grafana/`:

| File | Purpose |
|------|---------|
| `subscription.yaml` | Grafana Operator from `community-operators` (**Manual** approval in sample—pair with install-plan approval or switch to Automatic). |
| `uwm-configmap.yaml` | Enables user workload monitoring (cluster-wide). |
| `grafana.yaml` | `Grafana` CR in `openshift-user-workload-monitoring` |
| `grafana-credentials.yaml` | Admin credentials (replace; prefer secrets management). |
| `grafana-route.yaml` | Route to Grafana UI |
| `grafana-ds.yaml` | `GrafanaDatasource` pointing at **Thanos querier** for user workload Prometheus (`https://thanos-querier.openshift-monitoring.svc:9091`) with a **Bearer token** |
| `cluster-role-binding.yaml` | Binds `grafana-sa` to `cluster-monitoring-view` |
| `dashboards/*.yaml` | Keycloak-focused Grafana dashboards (Grafana Operator format) |

**Important:** `grafana-ds.yaml` uses a long-lived service account token in `secureJsonData`. For real deployments, **do not commit live tokens**. Generate a token at deploy time, for example:

```bash
oc create token grafana-sa -n openshift-user-workload-monitoring --duration=8760h
```

Store it in a Secret or use your secret manager and wire the datasource through GitOps sealed secrets or Argo CD vault plugins.

Create the **`grafana-sa`** ServiceAccount in `openshift-user-workload-monitoring` if it does not exist (the sample `Grafana` CR references it).

---

## Tracing: MinIO, Tempo, Keycloak OTLP

### Order

1. **MinIO** (optional in-cluster S3): `minio/minio.yaml` deploys MinIO in namespace `minio` with a PVC and Routes.
2. **Create bucket** (e.g. `keycloak`) in MinIO matching `tempo/tempostack-minio-secret.yaml`.
3. **Tempo Operator**: `tempo/subscription.yaml` installs from `redhat-operators` into `openshift-tempo-operator`.
4. **TempoStack** + storage secret: `tempo/tempostack-minio-secret.yaml` + `tempo/tempoStack.yaml` in namespace `tempo`.

The sample secret references:

`http://minio-service.minio.svc.cluster.local:9000`

### Keycloak

Point OTLP to the Tempo **distributor** gRPC endpoint. Example from `rhbk26/keycloak.yaml`:

```yaml
- name: tracing-enabled
  value: 'true'
- name: tracing-endpoint
  value: 'http://tempo-sample-distributor.tempo.svc.cluster.local:4317'
```

Service names depend on your **TempoStack** `metadata.name` and namespace (here: instance `sample` in `tempo` → distributor service name pattern as deployed by the operator).

`tempo/tempoStack.yaml` enables **Jaeger Query** with a **Route** for trace search UI.

---

## Argo CD integration

Install [OpenShift GitOps](https://docs.openshift.com/gitops/) (Argo CD) if not already present. Example applications live in `argo/`:

| Application manifest | Sync path | Notes |
|---------------------|-----------|--------|
| `argo/crunchy.yaml` | `crunchy/` | Crunchy operator + cluster |
| `argo/keycloak-operator.yaml` | `rhbk26/GitOps/rhbk-operator/base` | Kustomize; RHBK operator |
| `argo/keycloak.yaml` | `rhbk26/` | **Plain manifests** for Keycloak and related files in that directory |
| `argo/keycloak-helm.yaml` | `rhbk26/GitOps/rhbk-helm` | Helm with `values.yaml` |
| `argo/grafana.yaml` | `Grafana/` | Recursive directory |
| `argo/minio.yaml` | `minio/` | MinIO for Tempo backend |
| `argo/tempo.yaml` | `tempo/` | Tempo operator resources + stack |

**Fork and update** `repoURL` and `targetRevision` in each file. Some samples use Argo CD `project: aprr`—create that `AppProject` or change to `default`.

**Do not** sync both `keycloak.yaml` and `keycloak-helm.yaml` to the same namespace unless you intend to migrate or run separate instances with distinct names/namespaces.

Typical sync order matches [Recommended deployment order](#recommended-deployment-order): Crunchy → operator → Keycloak → monitoring → MinIO → Tempo.

---

## Optional components

- **`Openshift_Oauth/`** — Examples for integrating Keycloak with OpenShift OAuth (clients, certificates).
- **`password-expiry-idm/`** — Custom authenticator SPI; build the JAR and supply via ConfigMap/image as in `keycloak.yaml` `unsupported.podTemplate`.
- **`rhbk26/kc-franceconnect-providers.yaml`**, **`custom-provider.yaml`** — Provider ConfigMaps referenced by the sample Keycloak CR.

---

## Security and production notes

- Replace all **passwords**, **tokens**, and **demo `pg_hba` rules** before production.
- Pin **operator channels** and **CSV** versions deliberately; test upgrades in a non-prod cluster.
- Use **TLS** and proper **Route**/`Ingress` settings for public URLs in `spec.hostname`.
- Restrict **image pull secrets** and registry access.
- For **compliance**, review FranceConnect and custom JARs, and your tracing/metrics data retention (Tempo, Prometheus).

---

## License and support

Configuration in this repository is provided as **examples**. Red Hat product behavior is defined by official documentation for **Red Hat build of Keycloak**, **OpenShift**, **Crunchy Postgres for Kubernetes**, and **OpenShift distributed tracing** (Tempo). Validate against your subscription and cluster version.
