# Terraform

Installs the Helm chart into an existing namespace. It does not create the API-key Secret: create it first
so the chart's security mount has something to mount.

```bash
cat > security.yml <<'YAML'
phoenixgraph:
  security:
    enabled: true
    keys:
      - name: dashboard
        token: replace-with-a-long-random-token
        role: admin
YAML

kubectl create namespace phoenixgraph
kubectl -n phoenixgraph create secret generic phoenixgraph-secrets \
  --from-file=security.yml=security.yml \
  --from-literal=dashboard-token=replace-with-a-long-random-token

cp deploy/terraform/terraform.tfvars.example deploy/terraform/terraform.tfvars
$EDITOR deploy/terraform/terraform.tfvars
terraform -chdir=deploy/terraform init
terraform -chdir=deploy/terraform plan
terraform -chdir=deploy/terraform apply
```

- The module pins the Helm provider; `terraform init` downloads it.
- The namespace must exist; the module does not create or delete it.
- Use a remote backend for state (S3/GCS/Terraform Cloud); local state in a shared repo is not safe.
- The chart does not terminate TLS; put an Ingress/reverse proxy in front (see `deploy/tls/README.md`).

`terraform fmt -check` and `terraform validate` are the checks; a `plan` needs a kubeconfig.
