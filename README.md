<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# JBCompanion

JBCompanion (Jailbreak Companion) is a companion app for Android CVE-based temporary root solutions.  
There are 2 delicate pieces to temporary root:
1. Lack of control between booting and temporary root after power cycles.
2. They are patched by firmware updates.

This app attempts to provide a framework to help mitigate these issues by installing as a system privileged app which can:
1. Have a boot receiver that runs code before temporary root is available
2. Survive firmware updates even if the vulnerability for your temporary root has been patched
3. Can be upgraded in place indefinitely with new features with no vulnerability needed

## Features

Currently this app only has one feature:
- **Samsung DMC** — read and fix the Download Mode Controller to unblock Odin/Download Mode when screen lock is enabled

## How it works

`libinject.so` ptraces into `system_server` and loads `libpayload.so`  
`libpayload.so` loads java dex `EvilPMS.main()` inside the system server process to run commands as the system_server `Package Manager`, thus allowing high privileged app installation commands  
It reinstalls itself with the `android.uid.system` shared UID to persist as a privileged system app across reboots.

## Building

```sh
make
```

## Credits

- D2 mechanism — https://github.com/polygraphene/DFReroot/blob/main/D2_Error.md
- Ptrace injection — https://github.com/beakthoven/TrickyStoreOSS
- Loading dex from injection — https://github.com/ThePedroo/ZygoteLoader
