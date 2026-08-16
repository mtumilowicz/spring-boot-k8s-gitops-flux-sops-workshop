# spring-boot-k8s-gitops-flux-sops-workshop

## References

* prerequisite workshops
  * [SOPS and age key workshop](https://github.com/mtumilowicz/sops-age-key-workshop)
  * [Kustomize workshop](https://github.com/mtumilowicz/kustomize-workshop)
  * [GitOps and Flux workshop](https://github.com/mtumilowicz/gitops-flux-workshop)
* [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
* [Jib Gradle plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin)
* [Kubernetes ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/)
* [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)

## Workshop

* purpose
  * integrates the concepts from the prerequisite workshops with a Spring Boot application
  * externalizes one shared `application.yml` from the application artifact
  * supplies environment-specific secrets without encrypting non-sensitive configuration
  * verifies the complete path from desired state to typed Spring Boot properties
* scope
  * focuses on the boundaries between Spring Boot, Kubernetes, Kustomize, Flux and SOPS
  * assumes familiarity with each deployment tool from the prerequisite workshops
  * does not introduce additional secret-management or GitOps concepts

## Configuration delivery

```text
gitops/base/application.yml
  -> Kustomize-generated ConfigMap
  -> /config/application.yml
  -> Spring Boot external configuration

environment secret.enc.yaml
  -> Flux and SOPS decryption
  -> Kubernetes Secret
  -> DEMO_TOKEN1 and DEMO_TOKEN2
  -> Spring Boot environment overrides

external file and secret overrides
  -> @ConfigurationProperties
  -> DemoTokenProperties
```

* `gitops/base/application.yml` is the only Spring Boot configuration file
* its default document is a prod-shaped template with `<to_be_replaced>` values
* its `local` profile document provides local token values
* Kustomize stores it in a ConfigMap shared by dev and prod
* the Deployment mounts the ConfigMap at `/config/application.yml`
* `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/` adds the mounted directory to
  Spring Boot's configuration search locations
* each overlay contains a SOPS-encrypted Secret with only its token values
* `secretKeyRef` exposes those values as `DEMO_TOKEN1` and `DEMO_TOKEN2`
* `DemoTokenProperties` provides the typed application boundary for the
  `demo` configuration namespace

Spring Boot converts a canonical property name to an environment-variable name
by:

1. replacing `.` with `_`
2. removing `-`
3. converting the result to uppercase

```text
demo.token1 -> DEMO_TOKEN1
demo.token2 -> DEMO_TOKEN2
```

Environment variables have higher precedence than `application.yml`.
`DEMO_TOKEN1` and `DEMO_TOKEN2` therefore override the corresponding
`<to_be_replaced>` values before `DemoTokenProperties` is bound.

The Secret values are not written into the mounted `application.yml`:

```text
Kubernetes Secret key demo-token1
  -> container environment variable DEMO_TOKEN1
  -> Spring property demo.token1
  -> DemoTokenProperties.token1
```

Spring Boot combines the mounted file and the container environment as property
sources. The environment has higher precedence, so the resolved Spring property
contains the Secret value while `/config/application.yml` remains unchanged and
still contains `<to_be_replaced>`.

The application image contains no `application.yml`. The same external file and
image are used in both environments; only the profile and Secret differ.

## Environment model

| Concern | dev | prod |
| --- | --- | --- |
| overlay | `gitops/overlays/dev` | `gitops/overlays/prod` |
| namespace | `spring-boot-k8s-gitops-flux-sops-workshop-dev` | `spring-boot-k8s-gitops-flux-sops-workshop-prod` |
| Spring profile | `dev` | `prod` |
| image tag | `latest` | `1.0.0` |
| encrypted secrets | `gitops/overlays/dev/secret.enc.yaml` | `gitops/overlays/prod/secret.enc.yaml` |
| decryption identity Secret | `k8s-plain-secrets-dev` | `k8s-plain-secrets-prod` |

The base defines the shared application configuration, Deployment and Service.
Each overlay owns only its namespace, profile, image tag and encrypted secret
values.

The base uses the sentinel image tag `must-be-set-by-overlay`. Each deployable
overlay must replace it with its desired tag. This keeps release versions out of
the base and makes a missing overlay override fail during Pod startup.

## Project structure

```text
.
├── .run/                         shared IntelliJ local run configuration
├── src/
│   ├── main/                     Spring Boot application
│   └── test/                     Docker Desktop integration test
├── gitops/
│   ├── base/                     application.yml and shared Kubernetes resources
│   ├── overlays/
│   │   ├── dev/                  dev profile and encrypted secrets
│   │   └── prod/                 prod profile and encrypted secrets
│   └── clusters/
│       ├── dev/                  dev Flux connection and identity bootstrap
│       └── prod/                 prod Flux connection and identity bootstrap
└── scripts/                      workshop key and encryption helpers
```

## Workshop constraints

* the application logs the configured tokens to make the delivery path observable
* production applications must not log secrets

## Local run

The shared IntelliJ configuration points Spring Boot to `gitops/base` and
activates the `local` profile.

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION=file:./gitops/base/ \
SPRING_PROFILES_ACTIVE=local \
./gradlew bootRun
```

## Integration test

* requires Docker Desktop Kubernetes, `kubectl`, Flux and the `docker-desktop` context
* Jib builds the application image directly in Docker Desktop without a Dockerfile
* verifies each profile and its external configuration through startup logs

```bash
./gradlew test
```
