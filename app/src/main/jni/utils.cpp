// Copyright 2026 Dakkshesh <beakthoven@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

#include "utils.hpp"

#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cinttypes>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "logging.hpp"

namespace {
constexpr size_t kMaxPathLength = 256;
constexpr size_t kStatusBufferSize = 128;
constexpr int kInvalidFd = -1;
constexpr uintptr_t kStackAlignment = 0xf;
} // namespace

ssize_t write_proc(int pid, uintptr_t remote_addr, const void *buf, size_t len) {
    if (!buf || len == 0) {
        LOGE("Invalid parameters for write_proc");
        return -1;
    }

    LOGV("Writing %zu bytes to PID %d at address %" PRIxPTR, len, pid, remote_addr);

    char proc_path[kMaxPathLength];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/mem", pid);

    UniqueFd proc_fd = open(proc_path, O_WRONLY | O_CLOEXEC);
    if (proc_fd == kInvalidFd) {
        PLOGE("Failed to open %s", proc_path);
        return -1;
    }

    ssize_t bytes_written = pwrite(proc_fd, buf, len, static_cast<off_t>(remote_addr));
    if (bytes_written == -1) {
        PLOGE("pwrite failed for address %" PRIxPTR, remote_addr);
    } else if (static_cast<size_t>(bytes_written) != len) {
        LOGW("Partial write: %zd bytes written, %zu expected", bytes_written, len);
    }

    return bytes_written;
}

ssize_t read_proc(int pid, uintptr_t remote_addr, void *buf, size_t len) {
    if (!buf || len == 0) {
        LOGE("Invalid parameters for read_proc");
        return -1;
    }

    LOGV("Reading %zu bytes from PID %d at address %" PRIxPTR, len, pid, remote_addr);

    char proc_path[kMaxPathLength];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/mem", pid);

    UniqueFd proc_fd = open(proc_path, O_RDONLY | O_CLOEXEC);
    if (proc_fd == kInvalidFd) {
        PLOGE("Failed to open %s", proc_path);
        return -1;
    }

    ssize_t bytes_read = pread(proc_fd, buf, len, static_cast<off_t>(remote_addr));
    if (bytes_read == -1) {
        PLOGE("pread failed for address %" PRIxPTR, remote_addr);
    } else if (static_cast<size_t>(bytes_read) != len) {
        LOGW("Partial read: %zd bytes read, %zu expected", bytes_read, len);
    }

    return bytes_read;
}

bool get_regs(int pid, struct user_regs_struct &regs) {
    LOGV("Getting registers for PID %d", pid);

    struct iovec reg_iov = {.iov_base = &regs, .iov_len = sizeof(struct user_regs_struct)};
    if (ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &reg_iov) == -1) {
        PLOGE("Failed to get register set for PID %d", pid);
        return false;
    }

    LOGV("Successfully retrieved registers for PID %d", pid);
    return true;
}

bool set_regs(int pid, struct user_regs_struct &regs) {
    LOGV("Setting registers for PID %d", pid);

    struct iovec reg_iov = {.iov_base = &regs, .iov_len = sizeof(struct user_regs_struct)};
    if (ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &reg_iov) == -1) {
        PLOGE("Failed to set register set for PID %d", pid);
        return false;
    }

    LOGV("Successfully set registers for PID %d", pid);
    return true;
}

static void *find_module_base(const std::vector<ts::MapInfo> &map_info, std::string_view module_suffix) {
    for (const auto &map : map_info) {
        if (map.offset == 0 && map.path.ends_with(module_suffix)) {
            LOGV("Found module base for '%.*s' at %p", static_cast<int>(module_suffix.length()), module_suffix.data(),
                 reinterpret_cast<void *>(map.start));
            return reinterpret_cast<void *>(map.start);
        }
    }

    LOGV("Module base not found for suffix '%.*s'", static_cast<int>(module_suffix.length()), module_suffix.data());
    return nullptr;
}

void *find_func_addr(const std::vector<ts::MapInfo> &local_map_info, const std::vector<ts::MapInfo> &remote_map_info,
                     std::string_view module_name, std::string_view function_name) {
    LOGV("Resolving function '%.*s' in module '%.*s'", static_cast<int>(function_name.length()), function_name.data(),
         static_cast<int>(module_name.length()), module_name.data());

    void *lib_handle = dlopen(module_name.data(), RTLD_NOW);
    if (!lib_handle) {
        LOGE("Failed to open library '%.*s': %s", static_cast<int>(module_name.length()), module_name.data(), dlerror());
        return nullptr;
    }

    auto lib_closer = [lib_handle]() {
        dlclose(lib_handle);
    };

    auto *symbol_addr = reinterpret_cast<uint8_t *>(dlsym(lib_handle, function_name.data()));
    if (!symbol_addr) {
        LOGE("Failed to find symbol '%.*s' in library '%.*s': %s", static_cast<int>(function_name.length()), function_name.data(),
             static_cast<int>(module_name.length()), module_name.data(), dlerror());
        lib_closer();
        return nullptr;
    }

    LOGV("Found symbol '%.*s' at local address %p", static_cast<int>(function_name.length()), function_name.data(), symbol_addr);
    lib_closer();

    auto *local_base = reinterpret_cast<uint8_t *>(find_module_base(local_map_info, module_name));
    if (!local_base) {
        LOGE("Failed to find local base address for module '%.*s'", static_cast<int>(module_name.length()), module_name.data());
        return nullptr;
    }

    auto *remote_base = reinterpret_cast<uint8_t *>(find_module_base(remote_map_info, module_name));
    if (!remote_base) {
        LOGE("Failed to find remote base address for module '%.*s'", static_cast<int>(module_name.length()), module_name.data());
        return nullptr;
    }

    ptrdiff_t symbol_offset = symbol_addr - local_base;
    auto *remote_symbol_addr = remote_base + symbol_offset;

    LOGV("Address translation: local_base=%p remote_base=%p offset=%td -> "
         "remote_addr=%p",
         local_base, remote_base, symbol_offset, remote_symbol_addr);

    return remote_symbol_addr;
}

void align_stack(struct user_regs_struct &regs, uintptr_t preserve_bytes) {
    regs.REG_SP = (regs.REG_SP - preserve_bytes) & ~kStackAlignment;
    LOGV("Stack aligned to %" PRIxPTR " (preserved %zu bytes)", static_cast<uintptr_t>(regs.REG_SP), preserve_bytes);
}

uintptr_t push_memory(int pid, struct user_regs_struct &regs, const void *data, size_t length) {
    if (!data || length == 0) {
        LOGE("Invalid parameters for push_memory: data=%p, length=%zu", data, length);
        return 0;
    }

    regs.REG_SP -= length;
    align_stack(regs);

    auto stack_addr = static_cast<uintptr_t>(regs.REG_SP);

    if (write_proc(pid, stack_addr, data, length) != static_cast<ssize_t>(length)) {
        LOGE("Failed to push %zu bytes to remote stack at %" PRIxPTR, length, stack_addr);
        return 0;
    }

    LOGV("Pushed %zu bytes to remote stack at %" PRIxPTR, length, stack_addr);
    return stack_addr;
}

uintptr_t push_string(int pid, struct user_regs_struct &regs, const char *str) {
    if (!str) {
        LOGE("Null string pointer passed to push_string");
        return 0;
    }

    size_t str_length = strlen(str) + 1;

    regs.REG_SP -= str_length;
    align_stack(regs);

    auto stack_addr = static_cast<uintptr_t>(regs.REG_SP);

    if (write_proc(pid, stack_addr, str, str_length) != static_cast<ssize_t>(str_length)) {
        LOGE("Failed to push string '%s' to remote stack", str);
        return 0;
    }

    LOGV("Pushed string '%s' (%zu bytes) to remote stack at %" PRIxPTR, str, str_length, stack_addr);
    return stack_addr;
}

namespace {
constexpr size_t kMaxRegisterArgs = 8;
void setup_args(struct user_regs_struct &regs, const std::vector<uintptr_t> &args) {
    for (size_t i = 0; i < std::min(args.size(), kMaxRegisterArgs); i++) {
        regs.regs[i] = args[i];
    }
}
} // namespace

static bool remote_pre_call(int pid, struct user_regs_struct &regs, uintptr_t func_addr, uintptr_t return_addr,
                     std::vector<uintptr_t> &args) {
    align_stack(regs);

    LOGV("Setting up remote function call to %" PRIxPTR " with %zu arguments", func_addr, args.size());
    for (size_t i = 0; i < args.size(); i++) {
        LOGV("  arg[%zu] = %p", i, reinterpret_cast<void *>(args[i]));
    }

    setup_args(regs, args);

    if (args.size() > kMaxRegisterArgs) {
        size_t stack_args_size = (args.size() - kMaxRegisterArgs) * sizeof(uintptr_t);
        align_stack(regs, stack_args_size);

        if (write_proc(pid, static_cast<uintptr_t>(regs.REG_SP), args.data() + kMaxRegisterArgs, stack_args_size) !=
            static_cast<ssize_t>(stack_args_size)) {
            LOGE("Failed to push stack arguments");
            return false;
        }
    }

    regs.regs[30] = return_addr;
    regs.REG_IP = func_addr;

    if (!set_regs(pid, regs)) {
        LOGE("Failed to set registers for remote function call");
        return false;
    }

    if (ptrace(PTRACE_CONT, pid, 0, 0) == -1) {
        PLOGE("Failed to continue remote process execution");
        return false;
    }

    LOGV("Remote function call initiated successfully");
    return true;
}

static uintptr_t remote_post_call(int pid, struct user_regs_struct &regs, uintptr_t expected_return_addr) {
    LOGV("Waiting for remote function call completion");

    int status;
    while (true) {
        if (!wait_for_trace(pid, &status, __WALL)) {
            LOGE("Failed to wait for remote function completion");
            return 0;
        }

        if (!get_regs(pid, regs)) {
            LOGE("Failed to get registers after remote call");
            return 0;
        }

        int stop_signal = WSTOPSIG(status);
        LOGV("Remote function stopped with signal: %s(%d) at address %p", sigabbrev_np(stop_signal), stop_signal,
             reinterpret_cast<void *>(regs.REG_IP));

        if (stop_signal == SIGSEGV) {
            if (static_cast<uintptr_t>(regs.REG_IP) != expected_return_addr) {
                LOGE("Function returned to unexpected address %p (expected %p)", reinterpret_cast<void *>(regs.REG_IP),
                     reinterpret_cast<void *>(expected_return_addr));

                siginfo_t crash_info;
                if (ptrace(PTRACE_GETSIGINFO, pid, 0, &crash_info) == 0) {
                    LOGE("Crash details: si_code=%d si_addr=%p", crash_info.si_code, crash_info.si_addr);
                } else {
                    PLOGE("Failed to get crash signal info");
                }
                return 0;
            }

            uintptr_t return_value = regs.REG_RET;
            LOGV("Remote function completed with return value: %p", reinterpret_cast<void *>(return_value));
            return return_value;
        }

        // Suppress benign signals (SIGCHLD from fork, etc.) and keep waiting
        // for the expected SIGSEGV at the return address.
        LOGV("Suppressing %s(%d) during remote call, continuing",
             sigabbrev_np(stop_signal), stop_signal);
        if (ptrace(PTRACE_CONT, pid, 0, 0) == -1) {
            PLOGE("Failed to continue after signal %s(%d)", sigabbrev_np(stop_signal), stop_signal);
            return 0;
        }
    }
}

uintptr_t remote_call(int pid, struct user_regs_struct &regs, uintptr_t func_addr, uintptr_t return_addr,
                      std::vector<uintptr_t> &args) {
    if (!remote_pre_call(pid, regs, func_addr, return_addr, args)) {
        LOGE("Failed to prepare remote function call");
        return 0;
    }
    return remote_post_call(pid, regs, return_addr);
}

bool wait_for_trace(int pid, int *status, int flags) {
    if (!status) {
        LOGE("Null status pointer passed to wait_for_trace");
        return false;
    }

    while (true) {
        pid_t result = waitpid(pid, status, flags);
        if (result == -1) {
            if (errno == EINTR) {
                LOGV("waitpid interrupted, retrying");
                continue;
            } else {
                PLOGE("waitpid failed for PID %d", pid);
                return false;
            }
        }

        if (!WIFSTOPPED(*status)) {
            LOGE("Process %d not stopped for trace: %s", pid, parse_status(*status).c_str());
            return false;
        }

        LOGV("Process %d stopped for trace with status: %s", pid, parse_status(*status).c_str());
        return true;
    }
}

std::string parse_status(int status) {
    char status_buf[kStatusBufferSize];

    if (WIFEXITED(status)) {
        snprintf(status_buf, sizeof(status_buf), "0x%x exited with code %d", status, WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        snprintf(status_buf, sizeof(status_buf), "0x%x terminated by signal %s(%d)", status, sigabbrev_np(WTERMSIG(status)),
                 WTERMSIG(status));
    } else if (WIFSTOPPED(status)) {
        int stop_signal = WSTOPSIG(status);
        snprintf(status_buf, sizeof(status_buf), "0x%x stopped by signal=%s(%d), event=%s", status, sigabbrev_np(stop_signal),
                 stop_signal, parse_ptrace_event(status));
    } else {
        snprintf(status_buf, sizeof(status_buf), "0x%x unknown status", status);
    }

    return std::string(status_buf);
}

void *find_module_return_addr(const std::vector<ts::MapInfo> &map_info, std::string_view module_suffix) {
    for (const auto &map : map_info) {
        if ((map.perms & PROT_EXEC) == 0 && map.path.ends_with(module_suffix)) {
            LOGV("Found return address region for '%.*s' at %p", static_cast<int>(module_suffix.length()), module_suffix.data(),
                 reinterpret_cast<void *>(map.start));
            return reinterpret_cast<void *>(map.start);
        }
    }

    LOGV("No return address region found for module suffix '%.*s'", static_cast<int>(module_suffix.length()), module_suffix.data());
    return nullptr;
}


