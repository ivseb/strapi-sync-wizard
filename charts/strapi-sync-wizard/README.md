# StrapiSyncWizard Helm Chart

This Helm chart deploys the StrapiSyncWizard application on Kubernetes and OpenShift clusters.

## Introduction

StrapiSyncWizard is an application for synchronizing Strapi CMS instances. This Helm chart provides a convenient way to deploy the application with all its dependencies.

## Prerequisites

- Kubernetes 1.19+ or OpenShift 4.6+
- Helm 3.2.0+
- PV provisioner support in the underlying infrastructure (if persistence is enabled)

## Installing the Chart

To install the chart with the release name `my-release`:

```bash
helm install my-release ./charts/strapi-sync-wizard
```

The command deploys StrapiSyncWizard on the Kubernetes cluster with default configuration. The [Parameters](#parameters) section lists the parameters that can be configured during installation.

## Uninstalling the Chart

To uninstall/delete the `my-release` deployment:

```bash
helm uninstall my-release
```

## Parameters

### Global parameters

| Name                      | Description                                     | Value           |
| ------------------------- | ----------------------------------------------- | --------------- |
| `replicaCount`            | Number of replicas                              | `1`             |
| `image.repository`        | Image repository                                | `strapi-sync-wizard` |
| `image.tag`               | Image tag                                       | `0.0.1`         |
| `image.pullPolicy`        | Image pull policy                               | `IfNotPresent`  |
| `imagePullSecrets`        | Image pull secrets                              | `[]`            |
| `nameOverride`            | Override the name of the chart                  | `""`            |
| `fullnameOverride`        | Override the full name of the chart             | `""`            |

### Application parameters

| Name                                | Description                                     | Value           |
| ----------------------------------- | ----------------------------------------------- | --------------- |
| `application.port`                  | Application port                                | `8080`          |
| `application.host`                  | Application host                                | `0.0.0.0`       |
| `application.developmentMode`       | Enable development mode                         | `false`         |
| `application.dataFolder`            | Data folder path                                | `data`          |
| `application.strapi.clientTimeout`  | Strapi client timeout in milliseconds           | `30000`         |
| `application.strapi.maxRetries`     | Strapi client max retries                       | `3`             |
| `application.httpProxy`             | HTTP proxy configuration                        | `""`            |
| `application.httpsProxy`            | HTTPS proxy configuration                       | `""`            |
| `application.noProxy`               | No proxy configuration                          | `""`            |

### Database parameters

| Name                                | Description                                     | Value           |
| ----------------------------------- | ----------------------------------------------- | --------------- |
| `database.driver`                   | Database driver                                 | `org.postgresql.Driver` |
| `database.url`                      | Database URL                                    | `jdbc:postgresql://postgres:5432/strapisync` |
| `database.username`                 | Database username                               | `postgres`      |
| `database.password`                 | Database password                               | `postgres`      |
| `database.maximumPoolSize`          | Maximum connection pool size                    | `3`             |
| `database.isAutoCommit`             | Enable auto-commit                              | `false`         |
| `database.transactionIsolation`     | Transaction isolation level                     | `TRANSACTION_REPEATABLE_READ` |
| `database.salt`                     | Database encryption salt                        | `""`            |
| `database.external`                 | Use external database                           | `false`         |
| `database.postgresql.enabled`       | Deploy PostgreSQL                               | `true`          |
| `database.postgresql.auth.username` | PostgreSQL username                             | `postgres`      |
| `database.postgresql.auth.password` | PostgreSQL password                             | `postgres`      |
| `database.postgresql.auth.database` | PostgreSQL database name                        | `strapisync`    |

### Persistence parameters

| Name                       | Description                                     | Value           |
| -------------------------- | ----------------------------------------------- | --------------- |
| `persistence.enabled`      | Enable persistence                              | `true`          |
| `persistence.accessMode`   | Access mode                                     | `ReadWriteOnce` |
| `persistence.size`         | Size of persistent volume                       | `1Gi`           |

### OpenShift parameters

| Name                                                | Description                                     | Value           |
| --------------------------------------------------- | ----------------------------------------------- | --------------- |
| `openshift.enabled`                                 | Enable OpenShift compatibility                  | `false`         |
| `openshift.route.enabled`                           | Create OpenShift Route                          | `true`          |
| `openshift.route.host`                              | Route hostname                                  | `""`            |
| `openshift.route.routerLabel`                       | Router label (defaults to "internal" if empty)  | `""`            |
| `openshift.route.tls.enabled`                       | Enable TLS for Route                            | `true`          |
| `openshift.route.tls.termination`                   | TLS termination type                            | `edge`          |
| `openshift.route.tls.insecureEdgeTerminationPolicy` | Insecure edge termination policy                | `Redirect`      |
| `openshift.scc.enabled`                             | Use Security Context Constraints                | `true`          |

## OpenShift Compatibility

This chart is compatible with OpenShift. To deploy on OpenShift, set the following values:

```yaml
openshift:
  enabled: true
```

This will:
1. Use the appropriate API versions for OpenShift
2. Create an OpenShift Route instead of a Kubernetes Ingress
3. Apply the necessary Security Context Constraints

## Configuration

### External Database

To use an external database, set the following values:

```yaml
database:
  external: true
  url: "jdbc:postgresql://your-database-host:5432/your-database-name"
  username: "your-username"
  password: "your-password"
```

### Persistence

By default, the application uses a PersistentVolumeClaim to store data. To disable persistence:

```yaml
persistence:
  enabled: false
```

## Troubleshooting

### Database Connection Issues

If you're experiencing database connection issues, check:

1. The database URL is correct
2. The database credentials are correct
3. The database is accessible from the Kubernetes cluster

### Pod Startup Issues

If the pod fails to start, check:

1. The pod logs: `kubectl logs -f <pod-name>`
2. The pod events: `kubectl describe pod <pod-name>`
