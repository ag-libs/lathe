# Shared environment for the Lathe demo (prepare.sh and dev/demo/beats/*.tape).
# Sourced with $R = repo root: an isolated copy of the sample Neovim config, the Lathe client from
# this checkout, an isolated server cache, and the spring-petclinic clone the demo runs on.
# The clone lives OUTSIDE this repo: the repo has its own .lathe/, so a clone inside it would attach
# to the Lathe workspace before :LatheInit sets it up.
export LATHE_DEMO_WORK="${LATHE_DEMO_WORK:-$HOME/lathe-demo}"
export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"
export LATHE_NVIM_DIR="$R/lathe-neovim/runtime" LATHE_CACHE="$LATHE_DEMO_WORK/cache"
# A development client sets up this version (the one `mvn install` put in ~/.m2), not its own.
export LATHE_VERSION="0.1.0-SNAPSHOT"
export PETCLINIC_URL="https://github.com/spring-projects/spring-petclinic.git"
export PETCLINIC_COMMIT="500158f732419217507c7656904b8e6aa1bcc0d6"
# The clone prepare.sh sets up for beats 1-6; beat 0 clones it afresh to $PETCLINIC_FRESH, the path
# its :LatheInit prompt shows on camera.
export PETCLINIC="$LATHE_DEMO_WORK/warm/spring-petclinic"
export PETCLINIC_FRESH="$LATHE_DEMO_WORK/spring-petclinic"
export PETCLINIC_MAIN="src/main/java/org/springframework/samples/petclinic/owner/Owner.java"
