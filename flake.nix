{
  description = "Clojure development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Core development tools
            clojure
            leiningen
            
            # Additional useful tools
            rlwrap  # For better REPL experience
            jdk17   # Java Development Kit
            
            # Optional but helpful development tools
            clj-kondo  # Linter
            babashka   # Scripting tool for Clojure
          ];

          # Environment variables
          shellHook = ''
            echo "Clojure Development Environment"
            echo "JDK Version: $(java -version 2>&1 | head -n 1)"
            echo "Leiningen Version: $(lein version)"
            echo "Clojure Version: $(clj --version)"
            
            # Set JAVA_HOME
            export JAVA_HOME=${pkgs.jdk17}/lib/openjdk
            
            # Increase maximum heap size for large projects
            export LEIN_JVM_OPTS="-Xmx4g"
          '';
        };
      }
    );
}