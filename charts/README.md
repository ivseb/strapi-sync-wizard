# StrapiSyncWizard Helm Charts

This directory contains Helm charts for deploying StrapiSyncWizard on Kubernetes and OpenShift clusters.

## Available Charts

- [strapi-sync-wizard](./strapi-sync-wizard/README.md): Deploys the StrapiSyncWizard application with all its dependencies.

## Prerequisites

- Kubernetes 1.19+ or OpenShift 4.6+
- Helm 3.2.0+

## Usage

### Installing a Chart

```bash
# Add the repository (if hosted)
# helm repo add strapi-sync-wizard https://your-repo-url.com

# Update the repository
# helm repo update

# Install the chart
helm install my-release ./charts/strapi-sync-wizard
```

### Upgrading a Chart

```bash
helm upgrade my-release ./charts/strapi-sync-wizard
```

### Uninstalling a Chart

```bash
helm uninstall my-release
```

## Development

### Testing a Chart

```bash
# Validate the chart
helm lint ./charts/strapi-sync-wizard

# Test the chart installation
helm install --dry-run --debug my-release ./charts/strapi-sync-wizard
```

### Packaging a Chart

```bash
helm package ./charts/strapi-sync-wizard
```

## License

This project is licensed under the terms of the license included in the repository.