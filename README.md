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
* integrates the concepts from the prerequisite workshops with a Spring Boot application
* externalizes one shared `application.yml` from the application artifact
* supplies environment-specific secrets without encrypting non-sensitive configuration

## repository structure
* shared configuration
  * `gitops/base/application.yml` contains the shared configuration
  * its default document contains `<to_be_replaced>` token values
  * its `local` profile document contains local token values
  * the base generates a ConfigMap and mounts it at `/config/application.yml`
  * `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/` adds the mounted file to
    Spring Boot's configuration locations
* secret configuration
  * each overlay contains its encrypted token values in a Kubernetes Secret
  * `secretKeyRef` exposes the values as `DEMO_TOKEN1` and `DEMO_TOKEN2`
  * the mounted `application.yml` retains its placeholder values
* property binding
  * Spring Boot converts canonical property names to environment-variable names
    * replaces `.` with `_`
    * removes `-`
    * converts the result to uppercase
  * `demo.token1` maps to `DEMO_TOKEN1`
  * `demo.token2` maps to `DEMO_TOKEN2`
  * the environment variables override the corresponding `<to_be_replaced>` values
  * `DemoTokenProperties` binds the resolved `demo` configuration namespace

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
