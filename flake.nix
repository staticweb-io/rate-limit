{
  description = "rate-limit";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      with pkgs; {
        devShells.default = mkShell {
          buildInputs = [
            clojure
            glibcLocales # rlwrap (used by clj) uses this
            jdk
            rlwrap
          ];
        };
      });
}
