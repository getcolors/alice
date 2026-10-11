{ pkgs, ... }:
{
  languages.python.enable = true;
  packages = with pkgs; [ uv openssh rsync ];
}
