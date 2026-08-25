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

# Supplying only the region makes the provider return that region's
# account-level default VPC. The region already determines the answer, so the
# deployment discovers it at runtime rather than carrying an opaque UUID that a
# reader cannot check and that goes stale when the account changes. Nothing is
# created here and no discovered UUID is ever written back to desired state.
data "digitalocean_vpc" "default" {
  region = "ams3"
}

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard §4.3): the account resource is named after the profile and lives in
# this stack's state, which is what makes its ownership decidable. Never
# reference a literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "alice-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/alice-fixture.pub"))
}

resource "digitalocean_droplet" "alice" {
  # The profile, unless desired state overrides it. Resolved before the render
  # so this line never branches (Compute Name Standard §2).
  name     = "alice-fixture"
  region   = "ams3"
  size     = "s-1vcpu-1gb-35gb-intel"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = data.digitalocean_vpc.default.id
  # Droplet keys are ForceNew: changing the set destroys and recreates the
  # Droplet rather than re-authorizing it. Rotation is a rebuild, never an edit.
  ssh_keys = [digitalocean_ssh_key.machine.id]

  lifecycle {
    prevent_destroy = true

    # The data source answers for the region; this asserts the answer really is
    # the account default. Without it, a region that returned some other VPC
    # would place the Droplet on an unexpected network silently.
    postcondition {
      condition     = data.digitalocean_vpc.default.default
      error_message = "The discovered regional VPC is not DigitalOcean's default VPC."
    }
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
