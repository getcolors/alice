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

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard §4.3): the account resource is named after the profile and lives in
# this stack's state, which is what makes its ownership decidable. Never
# reference a literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "alice-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/alice-fixture.pub"))
}

resource "digitalocean_droplet" "alice" {
  name     = "alice-test"
  region   = "ams3"
  size     = "s-1vcpu-1gb-35gb-intel"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = "00000000-0000-4000-8000-000000000000"
  # Droplet keys are ForceNew: changing the set destroys and recreates the
  # Droplet rather than re-authorizing it. Rotation is a rebuild, never an edit.
  ssh_keys = [digitalocean_ssh_key.machine.id]

  lifecycle {
    prevent_destroy = true
  }
}

output "params" {
  value = {
    ip     = digitalocean_droplet.alice.ipv4_address
    name   = "alice-fixture"
    sudoer = "root"
    user   = "root"
    ssh_key_id = digitalocean_ssh_key.machine.id
  }
}
