#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>

#include <plti.h>

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
  struct plti ctx;
  plti_init(&ctx);

  #ifdef __aarch64__
    plti_add_lib(&ctx, "/apex/com.android.art/bin/dex2oat64");
  #else
    plti_add_lib(&ctx, "/apex/com.android.art/bin/dex2oat");
  #endif

  plti_add_hook(&ctx, "dex2oat", "_ZN3art15CompilerOptionsC1Ev", _ZN3art15CompilerOptionsC1Ev, NULL);

  plti_deinit(&ctx);

  LOGD("Hook registered successfully");
}