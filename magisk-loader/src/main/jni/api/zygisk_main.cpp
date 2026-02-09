/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#include <sys/socket.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/mman.h>

#include "zygisk.h"
#include "logging.h"
#include "loader.h"
#include "config_impl.h"
#include "magisk_loader.h"
#include "symbol_cache.h"

namespace lspd {

static ssize_t write_all(int fd, const void *buf, size_t count) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t written = 0;
    while (written < count) {
        ssize_t w = write(fd, p + written, count - written);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        written += (size_t)w;
    }
    return (ssize_t)written;
}

static ssize_t read_all(int fd, void *buf, size_t count) {
    uint8_t *p = (uint8_t *)buf;
    size_t read_bytes = 0;
    while (read_bytes < count) {
        ssize_t r = read(fd, p + read_bytes, count - read_bytes);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return (ssize_t)read_bytes; // EOF
        read_bytes += (size_t)r;
    }
    return (ssize_t)read_bytes;
}

    int allow_unload = 0;
    int *allowUnload = &allow_unload;
    bool should_ignore = false;

    class ZygiskModule : public zygisk::ModuleBase {
        JNIEnv *env_;
        zygisk::Api *api_;

        void onLoad(zygisk::Api *api, JNIEnv *env) override {
            env_ = env;
            api_ = api;
            MagiskLoader::Init();
            ConfigImpl::Init();
        }

        void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
            int cfd = api_->connectCompanion();
            if (cfd < 0) {
                LOGE("Failed to connect to companion: {}", strerror(errno));
                return;
            }

            uint8_t is_targeted = 1;
	    const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
	    if (!name) {
		LOGE("Failed to get process name");

		close(cfd);
		should_ignore = true;

		return;
            }

            uint8_t req_type = 1;
            uint32_t name_len = (uint32_t)strlen(name);
            int32_t scope_user_id = static_cast<int32_t>(args->uid / 100000);

            if (write_all(cfd, &req_type, sizeof(req_type)) < 0 ||
            	write_all(cfd, &name_len, sizeof(name_len)) < 0 ||
            	write_all(cfd, name, name_len) != static_cast<ssize_t>(name_len) ||
            	write_all(cfd, &scope_user_id, sizeof(scope_user_id)) < 0) {
            	LOGE("Failed to write to companion socket: {}", strerror(errno));
            
            	close(cfd);
            	should_ignore = true;

            	return;
            }
            // Read single-byte response: is_targeted
            uint8_t target_byte = 1;
            ssize_t r = read_all(cfd, &target_byte, sizeof(target_byte));
            if (r <= 0) {
            	LOGE("Failed to read is_targeted from companion socket: {}", strerror(errno));

            	close(cfd);
            	should_ignore = true;

            	return;
            }

	    is_targeted = target_byte;

            if (!is_targeted && strcmp(name, "com.android.shell") != 0 && strcmp(name, "org.lsposed.manager") != 0) {
            	LOGD("Process {} is not targeted by any module, skipping injection", name);

                env_->ReleaseStringUTFChars(args->nice_name, name);
		close(cfd);
                should_ignore = true;

                return;
            }

	    close(cfd);

            MagiskLoader::GetInstance()->OnNativeForkAndSpecializePre(
            	env_, args->uid, args->gids, args->nice_name,
            	args->is_child_zygote ? *args->is_child_zygote : false, args->app_data_dir);
        }

        void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
            if (should_ignore) {
                LOGD("Ignoring postAppSpecialize due to earlier skip (process not targeted)");
                api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
                return;
            }

            MagiskLoader::GetInstance()->OnNativeForkAndSpecializePost(env_, args->nice_name, args->app_data_dir);
            if (*allowUnload) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }

        void preServerSpecialize([[maybe_unused]] zygisk::ServerSpecializeArgs *args) override {
            MagiskLoader::GetInstance()->OnNativeForkSystemServerPre(env_);
        }

        void postServerSpecialize([[maybe_unused]] const zygisk::ServerSpecializeArgs *args) override {
            if (__system_property_find("ro.vendor.product.ztename")) {
                auto *process = env_->FindClass("android/os/Process");
                auto *set_argv0 = env_->GetStaticMethodID(process, "setArgV0",
                                                          "(Ljava/lang/String;)V");
                auto *name = env_->NewStringUTF("system_server");
                env_->CallStaticVoidMethod(process, set_argv0, name);
                env_->DeleteLocalRef(name);
                env_->DeleteLocalRef(process);
            }
            MagiskLoader::GetInstance()->OnNativeForkSystemServerPost(env_);
            if (*allowUnload) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    };
} //namespace lspd

/* TODO: Perhaps keep libsqlite loaded? */
static bool is_targeted_by_any_module(const char *package_name, int user_id) {
  void *lib = dlopen("libsqlite.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) lib = dlopen("libsqlite3.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) {
    LOGE("Failed to dlopen sqlite: {}", dlerror());

    return false;
  }

  typedef struct sqlite3 sqlite3;
  typedef struct sqlite3_stmt sqlite3_stmt;

  int (*sqlite3_open)(const char *, sqlite3 **) = (int (*)(const char *, sqlite3 **))dlsym(lib, "sqlite3_open");
  int (*sqlite3_prepare_v2)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **) = (int (*)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **))dlsym(lib, "sqlite3_prepare_v2");
  int (*sqlite3_bind_text)(sqlite3_stmt *, int, const char *, int, void (*)(void *)) = (int (*)(sqlite3_stmt *, int, const char *, int, void (*)(void *)))dlsym(lib, "sqlite3_bind_text");
  int (*sqlite3_bind_int)(sqlite3_stmt *, int, int) = (int (*)(sqlite3_stmt *, int, int))dlsym(lib, "sqlite3_bind_int");
  int (*sqlite3_step)(sqlite3_stmt *) = (int (*)(sqlite3_stmt *))dlsym(lib, "sqlite3_step");
  int (*sqlite3_finalize)(sqlite3_stmt *) = (int (*)(sqlite3_stmt *))dlsym(lib, "sqlite3_finalize");
  int (*sqlite3_close)(sqlite3 *) = (int (*)(sqlite3 *))dlsym(lib, "sqlite3_close");

  if (!sqlite3_open || !sqlite3_prepare_v2 || !sqlite3_bind_text || !sqlite3_bind_int || !sqlite3_step || !sqlite3_finalize || !sqlite3_close) {
    LOGE("Missing sqlite symbols");

    dlclose(lib);

    return false;
  }

  sqlite3 *db = NULL;
  const char *db_path = "/data/adb/lspd/config/modules_config.db";
  if (sqlite3_open(db_path, &db) != 0 || db == NULL) {
    LOGE("Failed to open sqlite db: {}", db_path);

    if (db) sqlite3_close(db);

    dlclose(lib);

    return false;
  }

  const char *sql = "SELECT 1 FROM scope INNER JOIN modules ON scope.mid = modules.mid WHERE scope.app_pkg_name = ? AND scope.user_id = ? AND modules.enabled = 1 LIMIT 1;";
  sqlite3_stmt *stmt = NULL;
  if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) != 0) {
    LOGE("Failed to prepare sqlite statement");

    if (db) sqlite3_close(db);

    dlclose(lib);

    return false;
  }

  if (sqlite3_bind_text(stmt, 1, package_name, (int)strlen(package_name), NULL) != 0) {
    LOGE("Failed to bind package name");
    
    if (stmt) sqlite3_finalize(stmt);
    if (db) sqlite3_close(db);

    dlclose(lib);

    return false;
  }
  
  if (sqlite3_bind_int(stmt, 2, user_id) != 0) {
    LOGE("Failed to bind user id");
    
    if (stmt) sqlite3_finalize(stmt);
    if (db) sqlite3_close(db);

    dlclose(lib);

    return false;
  }

  if (sqlite3_step(stmt) == 100) {
    sqlite3_finalize(stmt);
    sqlite3_close(db);
    dlclose(lib);

    return true;
  }

  sqlite3_finalize(stmt);
  sqlite3_close(db);
  dlclose(lib);

  return false;
}

void relsposed_companion(int lib_fd) {
  #define CLEAN_EXIT() \
    close(lib_fd);     \
                       \
    return

  uint8_t req_type = 0;
  if (lspd::read_all(lib_fd, &req_type, sizeof(req_type)) != sizeof(req_type)) {
    LOGE("Failed to read request type from companion socket: {}", strerror(errno));

    CLEAN_EXIT();
  }
  
  if (req_type != 1) {
    LOGE("Unsupported request type: {}", req_type);

    CLEAN_EXIT();
  }
  
  uint32_t name_len = 0;
  if (lspd::read_all(lib_fd, &name_len, sizeof(name_len)) != sizeof(name_len)) {
    LOGE("Failed to read name length from companion socket: {}", strerror(errno));

    CLEAN_EXIT();
  }

  if (name_len < 0 || name_len > 4096) {
    LOGE("Invalid name length: %u", name_len);

    CLEAN_EXIT();
  }
  
  std::string name;
  name.resize(name_len);
  if (lspd::read_all(lib_fd, &name[0], name_len) != name_len) {
    LOGE("Failed to read name from companion socket: {}", strerror(errno));

    CLEAN_EXIT();
  }
  
  LOGD("Received request for package '{}'", name.c_str());
  
  int32_t user_id = 0;
  if (lspd::read_all(lib_fd, &user_id, sizeof(user_id)) != sizeof(user_id)) {
    LOGE("Failed to read user id from companion socket: {}", strerror(errno));
    
    CLEAN_EXIT();
  }
  
  LOGD("Checking if package '{}' (user_id={}) is targeted by any module", name.c_str(), user_id);
  
  bool targeted = is_targeted_by_any_module(name.c_str(), user_id);
  uint8_t targeted_b = targeted ? 1 : 0;
  LOGD("Package '{}' (user_id={}) is {}targeted by any module", name.c_str(), user_id, targeted ? "" : "not ");
  if (lspd::write_all(lib_fd, &targeted_b, sizeof(targeted_b)) < 0) {
    LOGE("Failed to write to companion socket: {}", strerror(errno));
  }

  CLEAN_EXIT();
}

REGISTER_ZYGISK_MODULE(lspd::ZygiskModule);
REGISTER_ZYGISK_COMPANION(relsposed_companion);