# F2.0.3-update2 Roadmap: Floating Window Enhancements & Notes Sync Fix

## Overview
Update1 established the Antigravity floating call bubble and resizable floating window. This update hardens that window for multitasking across apps and fixes Notes persistence synchronization.

---

## Checklist

- [x] **Persistent System Overlay Window**
  - Migrate from translucent `ComponentActivity` (`FloatingChatActivity`) to Android `WindowManager` with `TYPE_APPLICATION_OVERLAY` via foreground service.
  - Ensures the floating window remains pinned as an on-screen overlay while interacting with other apps (studying, reading PDFs, browser).

- [x] **Background Touch Pass-Through**
  - Set `FLAG_NOT_TOUCH_MODAL` + `FLAG_WATCH_OUTSIDE_TOUCH` + `FLAG_HARDWARE_ACCELERATED`. Removed `FLAG_LAYOUT_IN_SCREEN` which was defeating pass-through by forcing the window region to full screen.

- [x] **Free-form Multi-Dimensional Resize**
  - Interactive edge and corner resize handles (bottom-right diagonal grip).
  - Smooth dynamic resizing of window width and height.

- [x] **Proportional Diagonal Scaling Mode**
  - Fixed dominant-axis pick to use relative (normalised) deltas so the axis doesn't flip erratically mid-drag.

- [x] **Responsive Layout Squeezing**
  - Switched `softInputMode` from the no-op `ADJUST_RESIZE` (ignored on overlay windows since Android 12) to `ADJUST_PAN`. All view panels use `fillMaxSize()` and adapt naturally. Bottom `ResizeGrip` moved from inside the clipped `Column` to the outer `Box` so its hit area is no longer clipped by `RoundedCornerShape`.

- [x] **Title Bar Drag & Reposition**
  - Drag handle on the header to move the floating window anywhere on screen.

- [x] **Minimize to Edge Bubble / PiP Pill**
  - One-tap collapse into a floating side bubble or picture-in-picture icon, tap to restore.

- [x] **Fix Notes MCP & UI Persistence Sync Bug**
  - Fix by preventing blind in-memory wipeouts on `init()`, flushing pending saves immediately once storage path resolves, atomic `.tmp` swap, and guarding `NotesRepository.reload()` against stale disk overwrites when in-flight user edits occur.
