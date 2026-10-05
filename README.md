# spring-boot-k8s-gitops-flux-sops-workshop

## References

* prerequisite workshops
  * [SOPS and age key workshop](https://github.com/mtumilowicz/sops-age-key-workshop)
  * [Kustomize workshop](https://github.com/mtumilowicz/kustomize-workshop)
  * [GitOps and Flux workshop](https://github.com/mtumilowicz/gitops-flux-workshop)
* [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
* [Kubernetes ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/)
* [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)
* [Kubernetes Secret practices](https://kubernetes.io/docs/concepts/security/secrets-good-practices/)
* [OWASP secrets management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
* [Kustomize generators, bases and overlays](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
* [Flux integration with SOPS](https://fluxcd.io/flux/guides/mozilla-sops/)
* [SOPS](https://github.com/getsops/sops)
* [Jib Gradle plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin)

## Workshop

* combines prerequisite concepts with Spring Boot
  * Kustomize: shared bases, environment overlays and configuration generators
  * GitOps and Flux: configuration in Git and continuous reconciliation
  * SOPS and age: encryption of secret values and decryption by Flux
* keeps `application.yml` outside the application artifact
  * configuration can change without rebuilding the application image
  * the same image can run with different configuration in each environment
* stores ordinary settings in a ConfigMap and environment-specific credentials
  in SOPS-encrypted Secret manifests
  * ordinary settings remain readable in Git
  * credentials are decrypted when Flux deploys the application
  * Kustomize generates a ConfigMap containing `application.yml`
  * Kubernetes mounts that file inside the application container
  * Flux decrypts the secret manifests and creates Kubernetes Secrets
  * Kubernetes supplies Secret values as container environment variables
  * Spring Boot gives those variables precedence over values declared in YAML
    * resolved properties use the secret values; the YAML file remains unchanged

## External configuration

* application code and deployment settings have separate lifecycles
  * example: deploy the same image with different database URLs and credentials
    in development and production
* Kubernetes stores configuration in API objects
  * a Pod must reference a ConfigMap or Secret to use its values
  * Kubernetes can create files in a volume mounted inside a container
  * Kubernetes can also set environment variables for a container's application process
  * creating a ConfigMap or Secret alone does not supply values to an application

### ConfigMap and Secret manifests

* ConfigMap: non-sensitive configuration
  * example: a ConfigMap entry whose value is a complete Spring Boot YAML file

    ```yaml
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: orders-config
    data:
      application.yml: | # the entry name becomes a filename when mounted
        server:
          port: 8080
    ```

  * when mounted at `/app/config`, the entry becomes
    `/app/config/application.yml` inside the container
* Secret: credentials and other sensitive configuration
  * example: a readable input manifest with a sample password

    ```yaml
    apiVersion: v1
    kind: Secret
    metadata:
      name: database-credentials
    type: Opaque
    stringData:
      spring.datasource.password: example-password
    ```

  * `stringData` accepts plaintext; Kubernetes stores it under `data` using base64
  * base64 is encoding, not encryption; neither representation is safe to commit
    with real credentials
  * use SOPS to encrypt values before committing the manifest
* Secret delivery recommendation
  * when the application supports files, prefer a read-only Secret volume
    over secret environment variables
    * environment variables can appear in diagnostics and are inherited by child processes
    * restrict file permissions and mount the volume only in containers that need it
  * file delivery does not protect secrets from a compromised application
  * this workshop uses environment variables to demonstrate Spring Boot overrides
  * alternative: mount the Secret above and let Spring Boot read its entry as a property

    ```yaml
    # Deployment fragment: inside spec.template.spec
    containers:
      - name: application
        env:
          - name: SPRING_CONFIG_IMPORT
            value: "configtree:/run/secrets/" # filenames become property names
        volumeMounts:
          - name: credentials
            mountPath: /run/secrets
            readOnly: true
    volumes:
      - name: credentials
        secret:
          secretName: database-credentials
    ```

  * Kubernetes creates `/run/secrets/spring.datasource.password` inside the container
  * Spring Boot reads that file's contents as the `spring.datasource.password` value
  * volume contents can update, but Spring Boot does not automatically reload properties

### Spring Boot configuration sources

* a property has a name and a value, for example `server.port=8080`
* several sources can supply the same property
  * YAML file

    ```yaml
    server:
      port: 8080
    ```

  * environment variable: `SERVER_PORT=8081`
  * command-line argument: `java -jar orders.jar --server.port=8082`
    * `--server.port=8082` supplies the property value directly
    * an argument can also select a file location, for example
      `--spring.config.additional-location=file:/app/config/`
* relevant precedence, highest first
  * this list covers ordinary application sources; Spring Boot also supports
    other sources, such as test properties

  1. Command-line arguments.
  2. Java system properties, for example `-Dserver.port=8083` before `-jar`.
  3. Operating-system environment variables.
  4. External profile files, such as `application-prod.yml`.
  5. External common files, such as `application.yml`.
  6. Profile files packaged in the application.
  7. Common files packaged in the application.
  8. Defaults set through `SpringApplication.setDefaultProperties`.

### Loading an external file

* example: `/app/config/application.yml` exists on the machine running the application
* set an environment variable before starting Java

  ```bash
  SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/ \
  java -jar orders.jar
  ```

* `SPRING_CONFIG_ADDITIONAL_LOCATION` is an environment variable understood by Spring Boot
* `file:/app/config/` tells Spring Boot to search the `/app/config/` directory
* Spring Boot loads `/app/config/application.yml` in addition to its default locations
* if the configured directory is missing, startup fails
  * if the directory is intentionally optional, use
    `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/`
  * `optional:` tells Spring Boot to continue when that location does not exist
  * required deployment configuration should use the form without `optional:`

### Profiles and property binding

* one YAML file can contain several documents separated by `---`
* example: `application.yml`

  ```yaml
  client:
    endpoint: https://api.example.com # common document
    api-key: "<supplied-externally>"
  --- # start another YAML document in the same file
  spring:
    config:
      activate:
        on-profile: local # load this document only when local is active
  client:
    endpoint: http://localhost:9000
    api-key: local-example-key
  ```

* `SPRING_PROFILES_ACTIVE=local` activates the `local` profile
  * Spring Boot then loads both documents; the later document overrides common values
  * `client.endpoint` resolves to `http://localhost:9000`
* environment-variable names follow Spring Boot's binding rules
  * replace dots with underscores
  * remove hyphens
  * convert to uppercase
  * examples: `client.api-key` → `CLIENT_APIKEY`;
    `spring.main.log-startup-info` → `SPRING_MAIN_LOGSTARTUPINFO`
* with `CLIENT_APIKEY=environment-example-key`, `client.api-key` resolves to
  `environment-example-key`, even when the `local` profile is active
  * the file still contains `<supplied-externally>` and `local-example-key`
  * Spring Boot combines configuration sources in memory; it never injects the
    environment-variable value into the YAML file
  * `<supplied-externally>` is ordinary text; it has no Spring substitution behavior
* `@ConfigurationProperties` gives application code typed access to resolved values
  * register property records with `@ConfigurationPropertiesScan` on the application class
  * example record

    ```java
    @ConfigurationProperties(prefix = "client")
    public record ClientProperties(String endpoint, String apiKey) {}
    ```

  * Spring Boot binds `client.endpoint` to `endpoint` and `client.api-key` to `apiKey`
  * with the local profile and environment variable above, the injected record contains

    ```java
    // Constructor example: Spring injects the configured ClientProperties instance.
    public ApiClient(ClientProperties properties) {
        this.endpoint = properties.endpoint(); // http://localhost:9000
        this.apiKey = properties.apiKey();     // environment-example-key
    }
    ```

### SOPS with bases and overlays

* keep shared non-sensitive configuration in the base
* keep each environment's credentials in its overlay
* encrypt the Secret values with SOPS before committing them
  * example before encryption: `stringData.password: example-password`
  * example committed to Git after encryption

    ```yaml
    apiVersion: v1
    kind: Secret
    metadata:
      name: database-credentials # the resource name is readable
    type: Opaque
    stringData:
      password: ENC[AES256_GCM,...] # abbreviated encrypted value
    sops: # SOPS also stores the metadata required for decryption
      # metadata omitted from this illustration
    ```

* SOPS changes the password value into ciphertext; the resource name and field names remain readable
* the shared ConfigMap needs no encryption because it contains no credentials
* Flux uses SOPS to decrypt secret manifests before applying them to Kubernetes
  * the application receives usable credentials from the resulting Secret
  * SOPS protects secrets in Git; cluster access controls and encryption at rest
    remain separate concerns
* for age key setup and Flux reconciliation, use the prerequisite workshops

## Kustomize generator examples

### `configMapGenerator`

* input: `config/application.yml`

  ```yaml
  server:
    port: 8080
  ```

* reason: edit the YAML file once; Kustomize inserts its contents into the ConfigMap
  * the output still contains those contents under `data.application.yml`
  * the generator saves maintaining a second copy by hand
* `kustomization.yaml`

  ```yaml
  resources:
    - deployment.yaml # include the Deployment so Kustomize can update its reference
  configMapGenerator:
    - name: orders-config
      files:
        # key=source-path: source contents become the value of this ConfigMap key
        - application.yml=config/application.yml
  ```

* generates

  ```yaml
  apiVersion: v1
  kind: ConfigMap
  metadata:
    name: orders-config-<content-hash>
  data:
    application.yml: |
      server:
        port: 8080
  ```

* mount the entry at `/app/config/application.yml` inside the application container
  * Deployment fragment

    ```yaml
    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: orders
    spec:
      template:
        spec:
          containers:
            - name: application
              env:
                - name: SPRING_CONFIG_ADDITIONAL_LOCATION # 4. tell Spring Boot where to read
                  value: file:/app/config/
              volumeMounts: # 3. make the volume visible inside this container
                - name: application-config
                  mountPath: /app/config
                  readOnly: true
          volumes: # 1. define the Pod volume
            - name: application-config
              configMap: # 2. create files from ConfigMap entries
                name: orders-config # Kustomize adds the generated hash here
    ```

### `secretGenerator`

* generates a Secret; it does not encrypt credentials or invoke SOPS
* useful when a deployment process obtains credentials outside Git
  * example: CI reads a secret store and creates a temporary `database.env` file
  * Kustomize reads that file, generates the Secret, and the deployment process applies it
  * the plaintext file and generated plaintext manifest stay outside Git
  * Flux cannot build this example from Git alone if `database.env` is absent
* input: temporary `database.env` with sample values

  ```dotenv
  SPRING_DATASOURCE_USERNAME=orders
  SPRING_DATASOURCE_PASSWORD=example-password
  ```

* `kustomization.yaml`

  ```yaml
  resources:
    - deployment.yaml # include the Deployment so Kustomize can update its reference
  secretGenerator:
    - name: database-credentials
      envs:
        - database.env # each line becomes a Secret entry
  ```

* generates

  ```yaml
  apiVersion: v1
  kind: Secret
  metadata:
    name: database-credentials-<content-hash>
  type: Opaque
  data:
    SPRING_DATASOURCE_USERNAME: b3JkZXJz # base64 of orders
    SPRING_DATASOURCE_PASSWORD: <base64-encoded-value>
  ```

* supply the Secret entries as environment variables to the application container
  * Deployment fragment

    ```yaml
    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: orders
    spec:
      template:
        spec:
          containers:
            - name: application
              envFrom:
                - secretRef: # each Secret key becomes an environment-variable name
                    name: database-credentials # Kustomize adds the generated hash here
    ```

* Spring Boot binds `SPRING_DATASOURCE_PASSWORD` to `spring.datasource.password`
* this repository uses SOPS to encrypt Secret manifests directly
  * Flux can decrypt the committed manifests without a separate plaintext input file
  * generating a Secret from `database.env` is therefore unnecessary here

### Generator placement and configuration changes

* shared generator: define it in the base; each overlay build includes it
* environment-specific generator: define it in the relevant overlay
* generated names contain a hash derived from the configuration contents
  * illustrative build output before a configuration change

    ```yaml
    # Generated ConfigMap
    metadata:
      name: orders-config-abc123
    ---
    # Deployment fragment: Kustomize rewrites the reference to match
    spec:
      template:
        spec:
          volumes:
            - name: application-config
              configMap:
                name: orders-config-abc123
    ```

  * after the file changes, both names change, for example to `orders-config-def456`
  * the Deployment's Pod template now references a different ConfigMap
  * Kubernetes replaces Pods so that the application starts with the changed configuration
  * generated Secrets follow the same rule when referenced by the Pod template
* a Secret manifest listed under `resources` keeps its declared name
  * SOPS encryption does not add a name hash
  * changing that Secret's values does not change the Deployment's Pod template
  * environment variables in existing containers keep their old values until containers restart

## Application in this repository

### Shared file and local development

* `gitops/base/application.yml` contains two YAML documents

  ```yaml
  demo:
    token1: "<to_be_replaced>" # supply the real value from another configuration source
    token2: "<to_be_replaced>"
  --- # the second document starts here
  spring:
    config:
      activate:
        on-profile: local # activate only for development outside Kubernetes
  demo:
    token1: "local-workshop-token1" # sample credentials for the developer's machine
    token2: "local-workshop-token2"
  ```

* Kubernetes deployments use Secret environment variables to override the markers
* local development activates `local` to use the sample values without Kubernetes
* Spring Boot validates `DemoTokenProperties` during configuration binding
  * `spring-boot-starter-validation` supplies Jakarta Bean Validation
  * `@Validated` enables validation on the configuration record
  * `@NotBlank` rejects missing, empty or whitespace-only values
  * `@Pattern` rejects `<to_be_replaced>`, including surrounding whitespace
  * invalid configuration prevents startup before the record is injected into application components

### ConfigMap and environment selection

* `gitops/base/kustomization.yaml` generates a ConfigMap from `application.yml`
* each overlay includes the base and generates the shared file in its own namespace
  * excerpt from `gitops/overlays/dev/kustomization.yaml`

    ```yaml
    namespace: spring-boot-k8s-gitops-flux-sops-workshop-dev # sets the namespace of namespaced resources
    resources:
      - namespace.yaml # creates the target Namespace
      - ../../base # includes the shared Deployment, Service and ConfigMap generator
      - secret.enc.yaml # includes this environment's encrypted Secret
    patches:
      - path: patch-deployment.yaml
    images:
      - name: spring-boot-k8s-gitops-flux-sops-workshop
        newTag: latest
    ```

* `patch-deployment.yaml` selects the Spring profile through a container environment variable

  ```yaml
  # Excerpt from the dev Deployment patch
  env:
    - name: SPRING_PROFILES_ACTIVE
      value: dev
  ```

* the prod overlay selects namespace `spring-boot-k8s-gitops-flux-sops-workshop-prod`,
  image tag `1.0.0` and Spring profile `prod`
* neither `dev` nor `prod` has a separate YAML document in `application.yml`
  * both use the common document and obtain token values from their own Secret
* the base Deployment mounts the generated file at `/config/application.yml`
  * the mount supplies Spring Boot configuration without packaging it in the image
  * `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/` tells Spring Boot to load
    that mounted file

### Encrypted tokens and Flux decryption

* each overlay commits `secret.enc.yaml` with the same Secret name in a different namespace
  * excerpt, with ciphertext and SOPS metadata abbreviated

    ```yaml
    apiVersion: v1
    kind: Secret
    metadata:
      name: spring-boot-k8s-gitops-flux-sops-workshop-secrets
    type: Opaque
    stringData:
      demo-token1: ENC[AES256_GCM,...] # encrypted before commit
      demo-token2: ENC[AES256_GCM,...]
    sops:
      # decryption metadata omitted from this illustration
    ```

* each overlay's `.sops.yaml` selects `data` and `stringData` for encryption
* Flux's `gitops/clusters/<environment>/app.yaml` enables decryption
  * dev excerpt

    ```yaml
    spec:
      path: ./gitops/overlays/dev
      decryption:
        provider: sops
        secretRef:
          name: k8s-plain-secrets-dev # Secret containing the age private key
    ```

* the prod workshop environment references `k8s-plain-secrets-prod`
  * `prod` is an example environment, not a production security model
  * both bootstrap manifests commit plaintext private keys for workshop simplicity
  * in a real system, provision private keys outside Git
* resolving `demo.token1`

  1. Git contains the encrypted `demo-token1` value in the selected overlay's `secret.enc.yaml`.
  2. Flux decrypts and applies `Secret/spring-boot-k8s-gitops-flux-sops-workshop-secrets`.
  3. The base Deployment selects its `demo-token1` key in the same namespace.

     ```yaml
     env:
       - name: DEMO_TOKEN1
         valueFrom:
           secretKeyRef:
             name: spring-boot-k8s-gitops-flux-sops-workshop-secrets
             key: demo-token1
     ```

  4. Kubernetes sets `DEMO_TOKEN1` to the decrypted value in the application container.
  5. Spring Boot resolves `demo.token1` from that environment variable instead of the YAML marker.
  6. `DemoTokenProperties.token1()` returns the resolved value.

* `demo.token2` follows the same path through `demo-token2` and `DEMO_TOKEN2`
* the mounted YAML file keeps its markers; application code receives resolved values
* the ConfigMap has a generated hash; these Secrets retain the fixed manifest name
  * Secret value changes require container restarts to refresh the environment variables

## Local run

* the shared IntelliJ configuration uses `gitops/base/application.yml` and activates `local`
* equivalent command

  ```bash
  SPRING_CONFIG_ADDITIONAL_LOCATION=file:./gitops/base/ \
  SPRING_PROFILES_ACTIVE=local \
  ./gradlew bootRun
  ```

## Integration test

* requires Docker Desktop Kubernetes, `kubectl`, Flux and the `docker-desktop` context
* Jib builds the application image in Docker Desktop without a Dockerfile
* `FluxDockerDesktopKubernetesTest` applies the dev and prod Flux configuration
* for each environment, the test reconciles Flux, restarts the Deployment and waits for rollout
* startup log assertions require
  * dev: `activeProfiles=[dev]`, `demo.token1=dev-workshop-token1`,
    `demo.token2=dev-workshop-token2`
  * prod: `activeProfiles=[prod]`, `demo.token1=prod-workshop-token1`,
    `demo.token2=prod-workshop-token2`
* these assertions confirm profile selection, SOPS decryption, Secret delivery
  and Spring Boot property binding
* the test deletes its Flux resources and application namespaces after execution
* token logging is for workshop sample values only; real applications must not log credentials

```bash
./gradlew test
```
