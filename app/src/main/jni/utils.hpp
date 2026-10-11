// Copyright 2026 Dakkshesh <beakthoven@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once
#include <sys/ptrace.h>
#include <unistd.h>

#include <string>

#include "maps.hpp"

#define REG_SP sp
#define REG_IP pc
#define REG_RET regs[0]

ssize_t write_proc(int pid, uintptr_t remote_addr, const void *buf, size_t len);
ssize_t read_proc(int pid, uintptr_t remote_addr, void *buf, size_t len);

bool get_regs(int pid, struct user_regs_struct &regs);
bool set_regs(int pid, struct user_regs_struct &regs);

void *find_func_addr(const std::vector<ts::MapInfo> &local_map_info, const std::vector<ts::MapInfo> &remote_map_info,
                     std::string_view module_name, std::string_view function_name);
void align_stack(struct user_regs_struct &regs, uintptr_t preserve_bytes = 0);
uintptr_t push_memory(int pid, struct user_regs_struct &regs, const void *data, size_t length);
uintptr_t push_string(int pid, struct user_regs_struct &regs, const char *str);

uintptr_t remote_call(int pid, struct user_regs_struct &regs, uintptr_t func_addr, uintptr_t return_addr,
                      std::vector<uintptr_t> &args);

bool wait_for_trace(int pid, int *status, int flags);
std::string parse_status(int status);
void *find_module_return_addr(const std::vector<ts::MapInfo> &map_info, std::string_view module_suffix);


class UniqueFd {
    using Fd = int;

public:
    UniqueFd() = default;
    UniqueFd(Fd fd) : fd_(fd) {}
    ~UniqueFd() {
        if (fd_ >= 0)
            close(fd_);
    }
    UniqueFd(const UniqueFd &) = delete;
    UniqueFd &operator=(const UniqueFd &) = delete;
    UniqueFd(UniqueFd &&other) {
        std::swap(fd_, other.fd_);
    }
    UniqueFd &operator=(UniqueFd &&other) {
        std::swap(fd_, other.fd_);
        return *this;
    }
    operator const Fd &() const {
        return fd_;
    }

private:
    Fd fd_ = -1;
};

#define CASE_CONST_RETURN(x) \
    case x:                  \
        return #x;
inline const char *parse_ptrace_event(int status) {
    status = status >> 16;
    switch (status) {
        CASE_CONST_RETURN(PTRACE_EVENT_FORK)
        CASE_CONST_RETURN(PTRACE_EVENT_VFORK)
        CASE_CONST_RETURN(PTRACE_EVENT_CLONE)
        CASE_CONST_RETURN(PTRACE_EVENT_EXEC)
        CASE_CONST_RETURN(PTRACE_EVENT_VFORK_DONE)
        CASE_CONST_RETURN(PTRACE_EVENT_EXIT)
        CASE_CONST_RETURN(PTRACE_EVENT_SECCOMP)
        CASE_CONST_RETURN(PTRACE_EVENT_STOP)
    default:
        return "(no event)";
    }
}
inline const char *sigabbrev_np(int sig) {
    if (sig > 0 && sig < NSIG)
        return sys_signame[sig];
    return "(unknown)";
}
