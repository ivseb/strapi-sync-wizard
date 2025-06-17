{{/*
Expand the name of the chart.
*/}}
{{- define "strapi-sync-wizard.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "strapi-sync-wizard.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "strapi-sync-wizard.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "strapi-sync-wizard.labels" -}}
helm.sh/chart: {{ include "strapi-sync-wizard.chart" . }}
{{ include "strapi-sync-wizard.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "strapi-sync-wizard.selectorLabels" -}}
app.kubernetes.io/name: {{ include "strapi-sync-wizard.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "strapi-sync-wizard.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "strapi-sync-wizard.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Return the appropriate apiVersion for deployment.
*/}}
{{- define "strapi-sync-wizard.deployment.apiVersion" -}}
apps/v1
{{- end -}}

{{/*
Return the appropriate apiVersion for ingress.
*/}}
{{- define "strapi-sync-wizard.ingress.apiVersion" -}}
{{- if .Capabilities.APIVersions.Has "networking.k8s.io/v1/Ingress" -}}
networking.k8s.io/v1
{{- else if .Capabilities.APIVersions.Has "networking.k8s.io/v1beta1/Ingress" -}}
networking.k8s.io/v1beta1
{{- else -}}
extensions/v1beta1
{{- end -}}
{{- end -}}

{{/*
Return the database URL
*/}}
{{- define "strapi-sync-wizard.databaseUrl" -}}
{{- if .Values.database.external -}}
{{ .Values.database.url }}
{{- else if .Values.database.postgresql.enabled -}}
jdbc:postgresql://{{ include "strapi-sync-wizard.fullname" . }}-postgresql:5432/{{ .Values.database.postgresql.auth.database }}
{{- else -}}
{{ .Values.database.url }}
{{- end -}}
{{- end -}}

{{/*
Return the database username
*/}}
{{- define "strapi-sync-wizard.databaseUsername" -}}
{{- if .Values.database.external -}}
{{ .Values.database.username }}
{{- else if .Values.database.postgresql.enabled -}}
{{ .Values.database.postgresql.auth.username }}
{{- else -}}
{{ .Values.database.username }}
{{- end -}}
{{- end -}}

{{/*
Return the database password
*/}}
{{- define "strapi-sync-wizard.databasePassword" -}}
{{- if .Values.database.external -}}
{{ .Values.database.password }}
{{- else if .Values.database.postgresql.enabled -}}
{{ .Values.database.postgresql.auth.password }}
{{- else -}}
{{ .Values.database.password }}
{{- end -}}
{{- end -}}