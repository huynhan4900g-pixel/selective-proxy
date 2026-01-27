# Setup Instructions

## `tun2socks` Binary

The application requires a `tun2socks` binary.
I have downloaded a compatible binary from `xjasonlyu/tun2socks` (v2.5.2, arm64) and placed it in `app/src/main/jniLibs/arm64-v8a/libtun2socks.so`.

**Why JNI Libs?**
Since Android 10 (API 29), executing binaries from the app's data directory is blocked for security. By packaging the binary as a `.so` library, Android extracts it to a system-managed location where execution is allowed.

**Note:** The user requested `outline-go-tun2socks`. The binary included is from `xjasonlyu/tun2socks` because `outline-go-tun2socks` does not publish standalone Linux/Android binaries in their releases (they publish AARs). This binary is highly compatible and supports the standard flags. If you strictly require the Outline build, you must compile it from source as described below.

**To compile `outline-go-tun2socks` yourself:**
1.  Clone `https://github.com/Jigsaw-Code/outline-go-tun2socks`.
2.  Compile it for Android using `gomobile` or `go build` with `GOOS=android GOARCH=arm64`.
3.  Rename the output binary to `libtun2socks.so`.
4.  Place it in `app/src/main/jniLibs/arm64-v8a/`.

## Android 14 Compliance

The code includes the necessary permissions and service type declarations for Android 14 (`FOREGROUND_SERVICE_CONNECTED_DEVICE`).
