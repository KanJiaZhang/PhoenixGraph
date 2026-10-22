terraform {
  required_version = ">= 1.6"
  required_providers {
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.17"
    }
  }
}

provider "helm" {
  kubernetes {
    config_path = pathexpand(var.kubeconfig_path)
  }
}

resource "helm_release" "phoenixgraph" {
  name      = var.release_name
  namespace = var.namespace
  chart     = "${path.module}/../helm/phoenixgraph"
  wait      = true
  timeout   = 600
  atomic    = true

  values = [
    yamlencode({
      image = {
        http = var.image_http
        web  = var.image_web
      }
      storage = {
        host  = var.storage_host
        seeds = var.storage_seeds
      }
      security = {
        enabled        = var.security_enabled
        existingSecret = var.security_secret_name
      }
      persistence = {
        size         = var.persistence_size
        storageClass = var.storage_class
      }
    })
  ]
}
