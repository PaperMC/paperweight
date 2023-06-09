# patch
steps:
* download mojang jar (and all other stuff in initial tasks)
* remap mojang jar
* copy resources
* decompile mojang jar
* line mapping (do we need that? no clue what exactly it does)
* download mc lib sources
* iterate over patch sets
  * clone stuff from upstream into work folder
  * iterate over patches
* for last patch set, clone stuff from upstream into Paper-Server (and Paper-API I guess?)

todo:
* how to handle ATs? do we check all patch sets and apply before applying patch sets?

# rebuild
steps:

todo:

# build
* shadow
* fix jar for reobf (hopefully not, lol)
* reobf
