{ config, pkgs, ... }:
{
  languages.python.enable = true;
  packages = with pkgs; [ uv openssh rsync ];
  scripts.alice.exec = ''
    exec uv run --locked --project "${config.devenv.root}" alice "$@"
  '';
}
