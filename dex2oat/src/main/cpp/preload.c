#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>

#include <lsplt.h>

#include "logging.h"

void _ZN3art15CompilerOptionsC1Ev(void *self) {
  unsetenv("LD_PRELOAD");

  void (*CompilerOptions)(void *) = (void (*)(void *))dlsym(RTLD_NEXT, "_ZN3art15CompilerOptionsC1Ev");
  if (!CompilerOptions) {
    LOGE("Failed to find original CompilerOptions constructor: %s", dlerror());
    
    return;
  }

  /* INFO: It updates the self structure, doesn't return anything */
  CompilerOptions(self);

  /* INFO: Try to find the inline_max_code_units_ member via heuristics and set it to 0. It
             set to -1 by default, which means no limit. Setting it to 0 effectively disables
             inlining, which is what we want to allow modules to hook all methods.

     SOURCES:
      - https://android.googlesource.com/platform/art/+/refs/tags/android-16.0.0_r1/compiler/driver/compiler_options.h#391
  */
  for (size_t i = 0; i <= 10 * sizeof(void *); i += sizeof(void *)) {
    void *member = *(void **)((void *)self + i);
    if (member != (void *)-1) continue;

    *(void **)((void *)self + i) = 0;

    return;
  }
  
  LOGE("Failed to find member with value -1 in CompilerOptions");
}

__attribute__((constructor))
static void oat_hook_init(void) {
   struct lsplt_map_info *maps = lsplt_scan_maps("self");
   if (!maps) {
    LOGE("Failed to scan maps");

    return;
   }
   
   dev_t dev = 0;
   ino_t inode = 0;
   
   for (size_t i = 0; i < maps->length; i++) {
     struct lsplt_map_entry *map = &maps->maps[i];
     if (!map->path || !strstr(map->path, "bin/dex2oat")) continue;
     
     dev = map->dev;
     inode = map->inode;
     
     LOGD("Found target: %s (dev: %ju, inode: %ju)", map->path, (uintmax_t)dev, (uintmax_t)inode);
     
     break;
   }
   
   if (!dev || !inode) {
     LOGE("Failed to find dex2oat memory map");

     lsplt_free_maps(maps);

     return;
   }
   
   lsplt_register_hook(dev, inode, "_ZN3art15CompilerOptionsC1Ev", _ZN3art15CompilerOptionsC1Ev, NULL);
   
   if (!lsplt_commit_hook()) {
     LOGE("Failed to commit hook");
   }
   
   LOGD("Hook registered successfully");
}