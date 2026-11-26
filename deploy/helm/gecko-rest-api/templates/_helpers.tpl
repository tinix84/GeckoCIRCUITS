{{- define "gecko-rest-api.selector" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "gecko-rest-api.secretName" -}}
{{- .Values.auth.existingSecret | default (printf "%s-auth" .Release.Name) -}}
{{- end -}}
