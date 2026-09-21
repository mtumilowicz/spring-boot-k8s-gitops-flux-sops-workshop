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

## Kustomize generator examples

* `configMapGenerator`
    * example: generate a ConfigMap containing `application.yml`
      * reason: use the original Spring Boot configuration file without including it in the container image or copying its contents into a ConfigMap manifest
      * `kustomization.yaml`
    
        ```yaml
        configMapGenerator:
          - name: spring-boot-k8s-gitops-flux-sops-workshop-config
            files:
              - application.yml=../config/application.yml # entries use `key=source-path`: key becomes an entry in the ConfigMap, source file contents become the entry's value
        ```
    
      * generates
    
        ```yaml
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: spring-boot-k8s-gitops-flux-sops-workshop-config-<content-hash>
        data:
          application.yml: |
            # contents of ../config/application.yml
        ```

      * mount the generated entry as a file and direct Spring Boot to its directory

        ```yaml
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: spring-boot-k8s-gitops-flux-sops-workshop
        spec:
          template:
            spec:
              containers:
                - name: application
                  env:
                    - name: SPRING_CONFIG_ADDITIONAL_LOCATION # 4. tell Spring Boot where to read pod volume
                      value: optional:file:/app/config/
                  volumeMounts: # 3. mount Pod volume into the container at /app/config
                    - name: application-config
                      mountPath: /app/config
                      readOnly: true
              volumes: # 1. creates a Pod volume named application-config using this ConfigMap
                - name: application-config
                  configMap: # 2. each ConfigMap key becomes a file in the mounted volume
                    name: spring-boot-k8s-gitops-flux-sops-workshop-config
        ```
* `secretGenerator`
    * example: generate a Secret containing Spring Boot database credentials
      * reason: provide credentials without including them in the container image or copying them into a Secret manifest
      * file: `database.env` (not committed, resolved locally)
        ```
        SPRING_DATASOURCE_USERNAME=workshop
        SPRING_DATASOURCE_PASSWORD=change-me
        ```
      * `kustomization.yaml`
        ```yaml
        secretGenerator:
          - name: database-credentials
            envs:
              - database.env # each entry becomes a key-value pair in the Secret
        ```
      * generates
  
        ```yaml
        apiVersion: v1
        kind: Secret
        metadata:
          name: database-credentials-<content-hash>
        type: Opaque
        data:
          SPRING_DATASOURCE_USERNAME: <base64-encoded-value>
          SPRING_DATASOURCE_PASSWORD: <base64-encoded-value>
        ```
      * expose the generated Secret entries as container environment variables
        ```yaml
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: spring-boot-k8s-gitops-flux-sops-workshop
        spec:
          template:
            spec:
              containers:
                - name: application
                  envFrom:
                    - secretRef: # adds every Secret entry as an environment variable
                        name: database-credentials
        ```
      * Spring Boot maps the environment variables to application properties
        * `SPRING_DATASOURCE_USERNAME` becomes `spring.datasource.username`
        * `SPRING_DATASOURCE_PASSWORD` becomes `spring.datasource.password`
      * generation does not encrypt secret values
        * Kubernetes stores these values as base64-encoded data
        * do not commit the plaintext `database.env` file

### Workshop flow: Git to Kubernetes

1. `GitRepository/gitops-flux-workshop` in namespace `flux-system` specifies:
   * repository URL
   * `main` branch
   * one-minute check interval
2. `source-controller` checks the repository
   * `branch: main` is a selection rule whose result can change
   * for each check, the controller resolves that rule to the exact commit
     currently referenced by `main`
3. `source-controller` creates a compressed artifact for that commit
   * the artifact contains the included repository files
   * the controller stores the file and serves it through its in-cluster HTTP
     service
   * it records the commit, digest and URL in
     `GitRepository.status.artifact`
4. `Kustomization/nginx-dev` in namespace `flux-system` reads:
   * `spec.sourceRef.name: gitops-flux-workshop` to select the source object
   * `spec.path: ./apps/nginx/overlays/dev` to select a directory inside the
     artifact
5. `kustomize-controller` downloads and verifies the artifact
6. `kustomize-controller` builds the selected directory
   * “build” means resolve the Kustomize resources, generators and patches into
     final Kubernetes manifests
   * in this workshop, the dev overlay combines the nginx base, dev
     `ConfigMap`, encrypted `Secret` and Deployment patch
7. `kustomize-controller` compares desired and live objects
   * it uses a server-side apply dry-run during periodic reconciliation
   * if a managed field differs, it applies the desired value through the
     Kubernetes API
   * if nothing differs, repeated reconciliation causes no effective object
     change
   * because `spec.prune: true`, it deletes previously managed objects that are
     absent from the build output
8. built-in Kubernetes controllers process the applied objects
   * example: the Deployment controller creates a ReplicaSet, and the ReplicaSet
     controller creates the requested Pods
9. Flux records the applied revision, inventory and conditions in
   `Kustomization/nginx-dev.status`
10. source changes, API watch events, intervals, retries and manual requests
    trigger later reconciliations

* reconciliation flow for the example
  1. `kustomize-controller` reads the live `Kustomization/web-prod` object
  2. `spec.sourceRef` identifies `GitRepository/platform-config` in the same
     namespace
  3. the controller reads the artifact revision, digest and URL from that source
     object's `status.artifact`
  4. it downloads the compressed artifact, verifies its digest and extracts it
     into a temporary directory
  5. `spec.path` selects `apps/web/overlays/prod` inside the extracted files
  6. the controller runs the Kustomize build
     * the build resolves the overlay's resources, generators and patches
     * the output is a stream of final Kubernetes manifests
     * this does not compile application code or build a container image
  7. it performs a server-side apply dry-run against the Kubernetes API
     * Kubernetes compares fields managed by Flux with the live objects
     * equal fields require no update
     * different or missing fields produce changes that Flux then applies
  8. if `spec.prune: true`, Flux uses the previous status inventory to delete
     managed objects that are absent from the new build output
  9. if `spec.wait: true`, Flux waits for supported applied objects to become
     ready
  10. it records the attempted revision, applied revision, managed-object
      inventory and readiness conditions in `Kustomization/web-prod.status`
  11. it deletes temporary data such as the downloaded archive and extracted
      repository files

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
