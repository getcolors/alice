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
  name     = "<{ digitalocean-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = "<{ digitalocean-vpc-uuid }>"
  ssh_keys = ["<{ digitalocean-ssh-keys }>"]

  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip     = digitalocean_droplet.alice.ipv4_address
    name   = "<{ profile }>"
    sudoer = "root"
    user   = "root"
  }
}
