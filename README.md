# spring-boot-k8s-gitops-flux-sops-workshop

## References

* prerequisite workshops
  * [SOPS and age key workshop](https://github.com/mtumilowicz/sops-age-key-workshop)
  * [Kustomize workshop](https://github.com/mtumilowicz/kustomize-workshop)
* [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
* [Flux GitRepository](https://fluxcd.io/flux/components/source/gitrepositories/)
* [Flux Kustomization](https://fluxcd.io/flux/components/kustomize/kustomizations/)
* [Flux SOPS decryption](https://fluxcd.io/flux/guides/mozilla-sops/)
* [Kubernetes Secret volumes](https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets-as-files-from-a-pod)

## Workshop warning

* the application logs its configured tokens to make the complete configuration path observable
* the repository commits disposable age private identities in Kubernetes Secret manifests
* both practices are limited to this workshop
* production applications must not log secrets or commit private identities

## Purpose

This workshop connects concepts introduced separately in the prerequisite
workshops:

```text
GitRepository
  -> Flux source-controller fetches the repository
  -> Flux kustomize-controller builds an environment overlay
  -> SOPS decrypts the application Secret during reconciliation
  -> Flux applies the Kubernetes resources
  -> kubelet mounts application.yml from the Secret
  -> Spring Boot loads and binds the external configuration
```

The important boundary is between encrypted Git state and runtime state:

* Git contains a SOPS-encrypted Kubernetes Secret
* Flux performs decryption inside the reconciliation process
* Kubernetes receives an ordinary Secret
* the pod receives a plaintext file mounted from that Secret
* Spring Boot does not interact with SOPS or age

## Project structure

```text
src/main/java/                         Spring Boot application
src/main/resources/application.yml     local fallback values
src/test/java/                         Docker Desktop integration test
gitops/base/                           shared Deployment and Service
gitops/overlays/dev/                   dev Namespace, patch, policy and Secret
gitops/overlays/prod/                  prod Namespace, patch, policy and Secret
gitops/clusters/dev/                   dev Flux source, reconciliation and key bootstrap
gitops/clusters/prod/                  prod Flux source, reconciliation and key bootstrap
scripts/                               age-key generation and SOPS helpers
```

## Spring Boot configuration loading

The classpath configuration supplies local fallback values:

```yaml
demo:
  token1: local-workshop-token1
  token2: local-workshop-token2
```

The Deployment adds an external configuration directory:

```yaml
env:
  - name: SPRING_CONFIG_ADDITIONAL_LOCATION
    value: file:/config/
```

`SPRING_CONFIG_ADDITIONAL_LOCATION` adds this directory without replacing the
default Spring Boot locations. Configuration loaded from `/config/` has higher
precedence than the packaged classpath file. The trailing `/` identifies a
directory, from which Spring Boot loads `application.yml`.

`DemoTokenProperties` binds the resulting `demo.token1` and `demo.token2`
values. Startup fails when either value is empty. `StartupTokenLogger` records
the active profile and resolved values as the workshop's end-to-end probe.

## Secret-to-file mapping

Each encrypted manifest represents a Kubernetes Secret with one key:

```yaml
stringData:
  application.yml: ENC[...]
```

The Deployment selects that key and mounts it as a file:

```yaml
volumes:
  - name: application-config
    secret:
      secretName: spring-boot-k8s-gitops-flux-sops-workshop-config
      items:
        - key: application.yml
          path: application.yml
```

```yaml
volumeMounts:
  - name: application-config
    mountPath: /config
    readOnly: true
```

The mapping is therefore:

```text
Secret.data[application.yml] -> /config/application.yml -> Spring Environment
```

The volume is read-only from the container's perspective. Updating the Secret
may update the mounted file, but this application reads and validates the
properties during startup. The workshop restarts the Deployment before checking
the resolved values.

## Flux source and reconciliation

Each environment defines two Flux resources in `flux-system`:

* `GitRepository`
  * polls the `main` branch of this GitHub repository every 30 seconds
  * produces the source artifact consumed by the environment reconciliation
* `Kustomization`
  * selects the corresponding overlay from that artifact
  * decrypts SOPS resources with the referenced age identity Secret
  * builds and applies the resources
  * waits for applied resources to become ready
  * removes previously managed resources that disappear from Git because
    `prune: true`

The Flux `Kustomization` is a reconciliation object. It is distinct from the
`kustomization.yaml` file in the selected overlay:

```text
Flux Kustomization.spec.path
  -> directory in the GitRepository artifact
  -> kustomization.yaml
  -> rendered Kubernetes resources
```

Flux periodically compares the declared Git state with cluster state. Manual
changes to managed resources can therefore be corrected on a later
reconciliation. A failed reconciliation is retried every 10 seconds, and one
attempt has a three-minute timeout.

## Dev and prod wiring

The environments use the same base but independent namespaces, profiles,
encrypted Secrets and decryption identities:

| Setting | dev | prod |
| --- | --- | --- |
| overlay | `gitops/overlays/dev` | `gitops/overlays/prod` |
| namespace suffix | `-dev` | `-prod` |
| Spring profile | `dev` | `prod` |
| Flux source | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| Flux reconciliation | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| decryption Secret | `k8s-plain-secrets-dev` | `k8s-plain-secrets-prod` |
| encrypted manifest | `gitops/overlays/dev/secret.enc.yaml` | `gitops/overlays/prod/secret.enc.yaml` |

Each overlay-local `.sops.yaml` contains that environment's Flux recipient and
a developer recipient. Each committed encrypted file records the recipients
used when it was encrypted.

The decryption Secret must exist before Flux can decrypt the application
Secret. This repository resolves that bootstrap dependency by including a
plaintext age identity manifest in each cluster directory:

```text
kubectl apply -k gitops/clusters/<environment>
  -> creates the age identity Secret in flux-system
  -> creates the GitRepository
  -> creates the Flux Kustomization
  -> reconciliation can decrypt the application Secret
```

This ordering is conceptual. Kubernetes accepts the three resources in one
apply operation, and Flux retries reconciliation until its referenced Secret is
available.

## Integration test

`./gradlew test` is an integration test against Docker Desktop Kubernetes. The
Gradle test task first builds the application JAR and the local image:

```text
spring-boot-k8s-gitops-flux-sops-workshop:latest
```

The Deployment uses `imagePullPolicy: Never`. Docker Desktop Kubernetes must
therefore be able to use that locally built image; no registry push is involved
for the image.

### Prerequisites

* Docker Desktop is running
* Docker Desktop Kubernetes is enabled
* the current Kubernetes context is `docker-desktop`
* `kubectl` is installed
* the Flux CLI is installed
* Flux controllers and CRDs are already installed in the cluster
* this repository is pushed to the configured GitHub URL on branch `main`
* the committed encrypted Secrets match the bootstrap age identities

Verify the client tools and context:

```bash
kubectl version --client
kubectl config current-context
flux --version
```

Expected context:

```text
docker-desktop
```

The test does not install Flux. Install it before running the test if the
cluster does not already contain Flux:

```bash
flux install
```

### Test flow

The test:

* applies `gitops/clusters/dev` and `gitops/clusters/prod`
* requests immediate reconciliation of each Flux Kustomization and its source
* restarts each Deployment
* waits for each rollout
* reads the application logs
* verifies the environment profile and both environment-specific tokens

Run:

```bash
./gradlew test
```

Flux fetches manifests from the configured GitHub repository. Uncommitted local
manifest changes are not part of that source artifact and are not exercised by
the test. The application image is the exception: Gradle builds it directly in
the local Docker Desktop image store.

After the test, cleanup deletes the two Flux Kustomizations, two GitRepository
resources and two application namespaces. It leaves the Flux installation and
the two bootstrap age identity Secrets in `flux-system`.

The log assertions prove this complete path:

```text
encrypted Git value
  -> Flux decryption
  -> Kubernetes Secret
  -> mounted application.yml
  -> Spring Boot property binding
```
