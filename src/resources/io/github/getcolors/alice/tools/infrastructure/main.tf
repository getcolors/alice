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

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard §4.3): the account resource is named after the profile and lives in
# this stack's state, which is what makes its ownership decidable. Never
# reference a literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "<{ profile }>"
  public_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %>resource "digitalocean_droplet" "alice" {
  name     = "<{ digitalocean-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = "<{ digitalocean-vpc-uuid }>"
  # Droplet keys are ForceNew: changing the set destroys and recreates the
  # Droplet rather than re-authorizing it. Rotation is a rebuild, never an edit.
<% if ssh-keygen %>  ssh_keys = [digitalocean_ssh_key.machine.id]
<% else %>  ssh_keys = ["<{ digitalocean-ssh-keys }>"]
<% endif %>
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
<% if ssh-keygen %>    ssh_key_id = digitalocean_ssh_key.machine.id
<% endif %>  }
}
