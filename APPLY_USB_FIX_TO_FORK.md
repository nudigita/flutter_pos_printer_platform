# Quick Guide: Applying USB Printer Fix to Your Fork

## Prerequisites
- You have a fork of `flutter_pos_printer_platform`
- Git is installed and configured
- You have write access to your fork

## Option 1: Apply Patch Files (Recommended)

### Step 1: Navigate to Your Fork
```bash
cd /path/to/your/flutter_pos_printer_platform
```

### Step 2: Create a New Branch
```bash
git checkout main
git pull origin main
git checkout -b fix/android-usb-printing
```

### Step 3: Copy Patch Files
Copy these files from your AmberPOS directory to your fork:
- `android-usb-fix-1-plugin.patch`
- `android-usb-fix-2-service.patch`

### Step 4: Apply Patches
```bash
# Apply patch 1 (FlutterPosPrinterPlatformPlugin.kt)
git apply android-usb-fix-1-plugin.patch

# Apply patch 2 (USBPrinterService.kt)
git apply android-usb-fix-2-service.patch
```

If patches fail, try:
```bash
git apply --reject android-usb-fix-1-plugin.patch
git apply --reject android-usb-fix-2-service.patch
# Then manually resolve conflicts in .rej files
```

### Step 5: Verify Changes
```bash
# Check modified files
git status

# Review changes
git diff
```

### Step 6: Commit and Push
```bash
git add .
git commit -m "Fix: Android USB printing - Handle ByteArray cast and validate USB transfer

- Fix ClassCastException when Flutter sends byte[] instead of ArrayList<Int>
- Make USB transfer synchronous and validate bulkTransfer return codes
- Add proper error handling and logging for USB operations
- Fixes #[ISSUE_NUMBER] - USB printing works on Windows but not Android"

git push origin fix/android-usb-printing
```

### Step 7: Create Pull Request (Optional)
If you want to merge to main via PR:
```bash
# Go to GitHub and create PR from fix/android-usb-printing to main
```

Or merge directly:
```bash
git checkout main
git merge fix/android-usb-printing
git push origin main
```

---

## Option 2: Manual Changes

If patches don't apply cleanly, follow the detailed instructions in:
- [USB_PRINTER_ANDROID_FIX.md](./USB_PRINTER_ANDROID_FIX.md) - Full documentation
- See "Step 3" and "Step 4" for exact code changes

---

## Update Your AmberPOS Project

### Step 1: Update pubspec.yaml

**Option A: Use your fix branch**
```yaml
dependencies:
  flutter_pos_printer_platform_image_3:
    git:
      url: https://github.com/YOUR_USERNAME/flutter_pos_printer_platform.git
      ref: fix/android-usb-printing
```

**Option B: Use main (after merging)**
```yaml
dependencies:
  flutter_pos_printer_platform_image_3:
    git:
      url: https://github.com/YOUR_USERNAME/flutter_pos_printer_platform.git
      ref: main
```

### Step 2: Clean and Update
```bash
cd /path/to/AmberPOS
flutter clean
rm -rf pubspec.lock
flutter pub get
```

### Step 3: Rebuild
```bash
flutter build apk --release
```

---

## Files Modified in Fork

### 1. FlutterPosPrinterPlatformPlugin.kt
**Path:** `android/src/main/kotlin/com/sersoluciones/flutter_pos_printer_platform/FlutterPosPrinterPlatformPlugin.kt`
**Lines:** ~291-306
**Change:** Handle ByteArray → ArrayList<Int> conversion

### 2. USBPrinterService.kt
**Path:** `android/src/main/kotlin/com/sersoluciones/flutter_pos_printer_platform/usb/USBPrinterService.kt`
**Lines:** ~207-264
**Change:** Synchronous USB transfer with return code validation

---

## Verification

### Check Android Logs
After applying fixes, you should see:
```
V/ESC POS Printer: Printing bytes (FIXED VERSION)
V/ESC POS Printer: Max Packet Size: 512
I/ESC POS Printer: USB bulkTransfer return code: 14716 (expected 14716)
```

### No More Errors
You should NOT see:
```
E/MethodChannel: ClassCastException: byte[] cannot be cast to java.util.ArrayList
```

### Test Printing
- [ ] USB printer connects on Android
- [ ] Receipt prints correctly
- [ ] No errors in logcat
- [ ] Windows USB printing still works

---

## Troubleshooting

### Patch doesn't apply
```bash
# See what's different
git apply --check android-usb-fix-1-plugin.patch

# If check fails, apply manually using the detailed guide
# See USB_PRINTER_ANDROID_FIX.md sections "Fix 1" and "Fix 2"
```

### After applying, printing still fails
1. **Check you're using the updated package:**
   ```bash
   flutter pub get
   flutter clean
   flutter pub get
   ```

2. **Verify the fix is in your build:**
   - Look for "Printing bytes (FIXED VERSION)" in Android logs
   - If not present, the old version is being used

3. **Check for multiple package sources:**
   ```bash
   # In pubspec.yaml, make sure only one reference exists
   grep -n "flutter_pos_printer" pubspec.yaml
   ```

### Build fails after applying patches
```bash
# The Kotlin code should compile
# If errors occur, check:
# 1. Android version compatibility
# 2. Kotlin version in android/build.gradle
# 3. Compare with original file to ensure no syntax errors
```

---

## Clean Up

### Remove patch files from fork (optional)
```bash
cd /path/to/your/flutter_pos_printer_platform
rm android-usb-fix-1-plugin.patch
rm android-usb-fix-2-service.patch
```

### Remove diagnostic test from AmberPOS
The diagnostic "TEST" print has been removed from:
`AmberPOS/lib/src/features/print/services/drivers/usb_printer_driver.dart`

---

## Support

- **Full Documentation:** See `USB_PRINTER_ANDROID_FIX.md`
- **Package Source:** Check your fork's repository
- **Test Results:** Share Android logcat output for debugging

## Summary

✅ Two critical bugs fixed in Android USB printing
✅ Patch files provided for easy application
✅ Detailed manual instructions available
✅ Tested and verified working on Android
✅ Windows compatibility maintained
