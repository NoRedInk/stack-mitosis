let
  sources = import ./nix/sources.nix { };
  pkgs = import sources.nixpkgs { };

  awsudo = pkgs.writeShellScriptBin "awsudo" ''
    exec ${pkgs.aws-vault}/bin/aws-vault exec \
      --duration="''${SUDO_DURATION:-1h}" "''${SUDO_ROLE:-sudo}" -- "$@"'';
in
pkgs.mkShell {
  buildInputs = [ awsudo pkgs.clojure pkgs.niv ];
}
