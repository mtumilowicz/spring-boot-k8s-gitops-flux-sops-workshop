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

## Workshop

* purpose
  * integrates Spring Boot, Kubernetes, Kustomize, Flux and SOPS
  * deploys the same application to dev and prod namespaces
  * supplies environment-specific Spring Boot secrets from encrypted Git state
  * verifies the complete configuration path through application startup logs
* configuration path

  ```text
  GitRepository
    -> Flux selects an environment overlay
    -> Flux decrypts and applies the Kubernetes Secret
    -> kubelet mounts application.yml from the Secret
    -> Spring Boot loads and binds the external configuration
  ```

* workshop-only behavior
  * the application logs configured tokens to make the path observable
  * the cluster manifests contain disposable age private identities
  * production applications must not log secrets or commit private identities

## Project structure

```text
.
├── Dockerfile
├── build.gradle
├── src/
│   ├── main/
│   │   ├── java/                       Spring Boot application
│   │   └── resources/application.yml  local fallback values
│   └── test/                           Docker Desktop integration test
├── gitops/
│   ├── base/                           shared Deployment and Service
│   ├── overlays/
│   │   ├── dev/                        dev Namespace, patch, policy and Secret
│   │   └── prod/                       prod Namespace, patch, policy and Secret
│   └── clusters/
│       ├── dev/                        dev Flux resources and key bootstrap
│       └── prod/                       prod Flux resources and key bootstrap
└── scripts/                            age-key generation and SOPS helpers
```

## Spring Boot configuration

* packaged configuration
  * `src/main/resources/application.yml` supplies local fallback values

    ```yaml
    demo:
      token1: local-workshop-token1
      token2: local-workshop-token2
    ```

* external configuration
  * the Deployment sets

    ```yaml
    env:
      - name: SPRING_CONFIG_ADDITIONAL_LOCATION
        value: file:/config/
    ```

  * the additional location preserves Spring Boot's default locations
  * `/config/application.yml` takes precedence over the packaged file
  * the trailing `/` identifies `/config/` as a directory

## Secret mount

* the encrypted Kubernetes Secret contains one application configuration entry

  ```yaml
  stringData:
    application.yml: ENC[...]
  ```

* the Deployment maps the entry to `/config/application.yml`

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

* resulting mapping

  ```text
  Secret.data[application.yml]
    -> /config/application.yml
    -> Spring Environment
    -> DemoTokenProperties
  ```

## Flux reconciliation

* source
  * each environment has a `GitRepository` in `flux-system`
  * both sources poll this repository's `main` branch every 30 seconds
  * each source produces an artifact for its environment's Flux `Kustomization`
* application
  * each Flux `Kustomization`
    * selects its environment overlay through `spec.path`
    * references its environment-specific SOPS identity Secret
    * waits for applied resources to become ready
    * removes managed resources deleted from Git because `prune: true`
* naming distinction
  * Flux `Kustomization` is a cluster reconciliation resource
  * `kustomization.yaml` is the Kustomize build file inside the selected directory

  ```text
  Flux Kustomization.spec.path
    -> GitRepository artifact directory
    -> kustomization.yaml
    -> rendered Kubernetes resources
  ```

* recovery
  * one reconciliation attempt has a three-minute timeout
  * a failed reconciliation is retried every 10 seconds
  * later reconciliations can correct manual changes to managed cluster resources

## Environment wiring

| Setting | dev | prod |
| --- | --- | --- |
| overlay | `gitops/overlays/dev` | `gitops/overlays/prod` |
| namespace | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| Spring profile | `dev` | `prod` |
| Flux source | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| Flux Kustomization | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| decryption Secret | `k8s-plain-secrets-dev` | `k8s-plain-secrets-prod` |
| encrypted Secret | `gitops/overlays/dev/secret.enc.yaml` | `gitops/overlays/prod/secret.enc.yaml` |

* shared resources
  * both overlays reuse `gitops/base`
* environment differences
  * each overlay selects its namespace, Spring profile and encrypted Secret
  * each cluster directory selects its source, overlay and decryption Secret
* key bootstrap
  * each cluster directory includes a plaintext age identity Secret for this workshop
  * the identity Secret must exist before Flux can decrypt the application Secret
  * Flux retries reconciliation if the identity is not yet available

  ```text
  kubectl apply -k gitops/clusters/<environment>
    -> age identity Secret in flux-system
    -> GitRepository
    -> Flux Kustomization
    -> environment overlay reconciliation
  ```

## Integration test

* prerequisites
  * Docker Desktop is running
  * Docker Desktop Kubernetes is enabled
  * the current Kubernetes context is `docker-desktop`
  * `kubectl` and the Flux CLI are installed
  * Flux controllers and CRDs are installed in the cluster
  * this repository is available at the configured GitHub URL on branch `main`
  * the encrypted Secrets match the committed bootstrap identities
* verify prerequisites

  ```bash
  kubectl version --client
  kubectl config current-context
  flux --version
  ```

  Expected context:

  ```text
  docker-desktop
  ```

  Install Flux when it is absent:

  ```bash
  flux install
  ```

* test flow
  * applies `gitops/clusters/dev` and `gitops/clusters/prod`
  * requests immediate reconciliation of each source and Flux `Kustomization`
  * restarts and waits for both Deployments
  * verifies the active profile and both configured tokens in each application log
* source boundary
  * Flux reads manifests from GitHub
  * uncommitted local manifest changes are not exercised
  * Gradle builds the application image directly in Docker Desktop, so the image does not require a registry push
* cleanup
  * deletes both Flux `Kustomization` resources
  * deletes both `GitRepository` resources
  * deletes both application namespaces
  * retains Flux and the bootstrap identity Secrets in `flux-system`
* run

  ```bash
  ./gradlew test
  ```
