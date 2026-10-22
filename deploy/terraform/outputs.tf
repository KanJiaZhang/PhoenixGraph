output "namespace" {
  value = var.namespace
}

output "release" {
  value = helm_release.phoenixgraph.name
}

output "api_service" {
  value = "${helm_release.phoenixgraph.name}-http"
}

output "ui_service" {
  value = "${helm_release.phoenixgraph.name}-web"
}
