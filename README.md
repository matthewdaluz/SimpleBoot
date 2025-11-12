## SimpleBoot is now a product of RedHead Industries, a non-profit organization that I have founded -- By the people, for the people. This does not mean SimpleBoot is now proprietary, this is simply a change of ownership (and where SimpleBoot's repository will be at).
## Check the new repository here: https://github.com/redhead-industries/SimpleBoot

# 📱 SimpleBoot

**SimpleBoot** is a powerful, root-required Android app that transforms your phone into a fully bootable USB device. Mount ISO/IMG files via **ConfigFS**, **Legacy mass_storage**, or raw **Loopback** mode and boot directly into live systems on any PC.

> Successor to DriveDroid and PhoneStick, built for Android 11+ with full modern kernel and ConfigFS support.

---

## 🚀 Features

- 🔍 **Automatic ISO/IMG discovery** from `/storage/emulated/0/SimpleBootISOs`
- 📦 **Three mount methods**:
  - `ConfigFS`: For most modern kernels
  - `Legacy`: For older Android USB stacks
  - `Loopback`: For direct mount w/o USB gadget (testing or chaining)
- 💿 **CD-ROM boot mode** with forced descriptors for BIOS/UEFI compatibility (Deprecated. To be removed in later builds.)
- 🔐 **Root shell integration** using [`libsu`](https://github.com/topjohnwu/libsu)
- 🧠 **Complete logging** of all mount/unmount operations (`SimpleBootLogs`)
- 🌓 **Light/Dark mode** Jetpack Compose UI
- 🛠️ **Per-ISO selection** of mount method
- 🧲 **Toggle USB Charging + ADB** from within the UI
- ⚙️ **Persistent preferences** for read-only mounting
- 📤 Export logs and diagnostics for debugging

---

## 📸 Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/81af67c3-1c10-40dc-bf7e-81a62158c33a" alt="Screenshot 1" width="200"/>
  <img src="https://github.com/user-attachments/assets/81af67c3-1c10-40dc-bf7e-81a62158c33a" alt="Screenshot 2" width="200"/>
</p>

## ⚙️ Requirements

- 📱 Android 11+ (API 30 or later)
- 📲 Root access (Magisk recommended)
- 🔌 OTG-capable USB port
- 📦 Kernel support for ConfigFS (most modern AOSP-based ROMs)
- 🧪 Optional: Legacy USB gadget stack for older devices

---

## 🗂 File System Layout

- `/storage/emulated/0/SimpleBootISOs/`  
  Drop your `.iso` or `.img` files here
- `/storage/emulated/0/SimpleBootLogs/`  
  Verbose mount/unmount logs
- `/storage/emulated/0/SimpleBootLogs/mount_log_YYYYMMDD.txt`  
  Daily logs for boot diagnostics
- `/dev/block/loopX`  
  Loop device usage via `losetup` (automatic)

---

## 💻 How It Works

1. You select an ISO from the UI
2. You choose the desired **mount method**
3. SimpleBoot sets up the USB gadget using ConfigFS or legacy nodes
4. It attaches the loop device and configures the gadget as a **bootable CD-ROM**
5. Your PC sees it as a USB boot drive — boot away!

---

## 📦 Mount Methods Explained

| Method     | Description                                                                 |
|------------|-----------------------------------------------------------------------------|
| `ConfigFS` | Modern gadget system. Uses `/config/usb_gadget/...`. Required for Android 11+ |
| `Legacy`   | Uses `/sys/class/android_usb/android0/` and `f_mass_storage`. Older devices |
| `Loopback` | Mounts ISO to `/dev/block/loop7` only (no USB exposure). For dev/testing    |

---

## ⚠️ Disclaimers

- 📛 This app **requires root** and **full filesystem access**
- 🧱 Misconfiguration or unsupported kernels may cause boot failures or USB stack issues
- ⚡ SimpleBoot tries to restore ADB/charging state on every unmount and mount failure

---

## 🛠️ Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [libsu](https://github.com/topjohnwu/libsu)
- [Kotlin](https://kotlinlang.org/)
- [Android 11+ Permissions API](https://developer.android.com/about/versions/11/privacy/storage)

---

## 📜 License

GNU GPLv3 – see [LICENSE](./LICENSE)

---

## 💬 Contribute

Pull requests welcome for:
- Additional mount backends (e.g. FFS + userspace)
- USB mode presets (keyboard, HID)
- Multi-ISO boot chains (Ventoy-style)
- USB detection callback API (notify when PC sees device)

---

## 🙏 Credits

- **Lead Dev:** [@matthewdaluz](https://github.com/matthewdaluz)
- **AI Assistants:** ChatGPT + DeepSeek
- **Special Thanks:** The open-source Android root community

---

## 🔚 Final Words

SimpleBoot gives Android users full control over USB boot from their pocket. Whether you're an IT tech, a Linux user, or just want to carry live systems on your phone — this tool is the bootloader companion you've been missing.

> ✨ Mount. Boot. Reboot. Simple. ✨
