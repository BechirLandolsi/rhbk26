{{- define "rhbk.name" -}}
{{- default .Release.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "rhbk.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "rhbk.name" . | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{- define "rhbk.labels" -}}
app.kubernetes.io/name: {{ include "rhbk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: rhbk
{{- with .Values.global.extraLabels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- /* useful for selectors (won’t include version/managed-by/etc.) */ -}}
{{- define "rhbk.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rhbk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "rhbk.annotations" -}}
{{- with .Values.global.annotations }}
{{ toYaml . }}
{{- end }}
{{- end }}
