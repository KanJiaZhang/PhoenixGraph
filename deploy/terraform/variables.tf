variable "kubeconfig_path" {
  description = "Path to the kubeconfig used by the Helm and Kubernetes providers."
  type        = string
  default     = "~/.kube/config"
}

variable "namespace" {
  description = "Namespace to create and install into."
  type        = string
  default     = "phoenixgraph"
}

variable "release_name" {
  description = "Helm release name."
  type        = string
  default     = "phoenixgraph"
}

variable "image_http" {
  description = "Engine image, pinned to a tag or digest."
  type        = string
  default     = "phoenixgraph-http:1.0.1"
}

variable "image_web" {
  description = "UI image, pinned to a tag or digest."
  type        = string
  default     = "phoenixgraph-web:1.0.1"
}

variable "storage_host" {
  description = "Aerospike host (or a load-balanced address). Use the chart values for a seed list."
  type        = string
  default     = "aerospike"
}

variable "storage_seeds" {
  description = "Optional Aerospike seed list (host:port); overrides storage_host."
  type        = list(string)
  default     = []
}

variable "security_enabled" {
  description = "Require an API key. The Secret must contain security.yml and dashboard-token."
  type        = bool
  default     = true
}

variable "security_secret_name" {
  type    = string
  default = "phoenixgraph-secrets"
}

variable "persistence_size" {
  type    = string
  default = "20Gi"
}

variable "storage_class" {
  description = "StorageClass for the engine PVC; empty uses the cluster default."
  type        = string
  default     = ""
}
