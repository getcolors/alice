terraform {
  required_version = ">= 1.8.0"
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "2.51.0"
    }
  }
}

# DIGITALOCEAN_TOKEN arrives only in the OpenTofu process environment.
provider "digitalocean" {}

resource "digitalocean_droplet" "alice" {
  # The profile, unless desired state overrides it. Resolved before the render
  # so this line never branches (Compute Name Standard §2).
  name     = "alice-test"
  region   = "ams3"
  size     = "s-1vcpu-1gb-35gb-intel"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = "00000000-0000-4000-8000-000000000000"
  # Droplet keys are ForceNew: changing the set destroys and recreates the
  # Droplet rather than re-authorizing it. Rotation is a rebuild, never an edit.
  ssh_keys = ["812184"]

  lifecycle {
    prevent_destroy = true
  }
}

output "params" {
  value = {
    ip     = digitalocean_droplet.alice.ipv4_address
    name   = "alice-optout-fixture"
    sudoer = "root"
    user   = "root"
  }
}
