# Union Bridge Android — v0.1.1 (label test)

Independent Android Bluetooth print bridge for Unionline.

## Current scope
- Paired Bluetooth Classic/SPP printers.
- TSPL/TSPL2-compatible label printing; default label is 40 × 30 mm.
- Updated label test layout: full product name in up to two lines, thin divider, small SKU on the left, moderate price on the right with L suffix, Code 128 barcode and small human-readable number below.
- ESC/POS receipt printing; intended first target is Rongta RP327.
- Separate saved printer selection for labels and receipts.
- Custom URI entry point: `unionbridge://print?payload=<base64url-json>`
- Test-label and test-receipt buttons.
- GitHub Actions debug APK build (Java 17, Android SDK 35).

## Build APK without Android Studio
1. Upload the extracted project folder contents to a GitHub repository (not the ZIP as a single file).
2. Push to `main` or `master`, or run **Actions → Build Union Bridge APK → Run workflow**.
3. Open the successful workflow run and download artifact `union-bridge-debug-apk`.
4. Extract the artifact ZIP and install `app-debug.apk` on the Android phone.

## First run
1. Pair both printers in Android Bluetooth settings.
2. Open Union Bridge and grant Bluetooth permissions.
3. Select the label printer and receipt printer separately.
4. Save the selections.
5. Test the receipt on the Rongta RP327. Test labels only after identifying the label printer model and confirming TSPL/TSPL2 support.

## Deep-link API

Payload is UTF-8 JSON encoded as URL-safe Base64 without padding. URL:

`unionbridge://print?payload=BASE64URL_JSON`

Receipt example JSON:
```json
{
  "type": "receipt",
  "receipt": {
    "company": "Baumarket",
    "title": "FATURE",
    "number": "F-1001",
    "date": "2026-10-25",
    "items": [
      {"name":"Produkt test","qty":"2","price":"500","total":"1000"}
    ],
    "subtotal":"1000 LEK",
    "total":"1000 LEK",
    "footer":"Faleminderit!"
  }
}
```

Label example JSON:
```json
{
  "type": "label",
  "label": {
    "company": "Baumarket",
    "name": "Produkt test",
    "sku": "SKU-0001",
    "price": "1250 LEK",
    "barcode": "SKU-0001",
    "widthMm": 40,
    "heightMm": 30,
    "language": "tspl2"
  }
}
```

Optional `printerMac` overrides the saved printer for this one request. Optional `copies` is limited to 1–100.

### Integration note
The website must build the JSON payload, encode it as URL-safe Base64, then navigate to the custom URI. Because browser/app hand-off behavior varies, test the final link from the Unionline PWA on the target Android phone. Do not pass sensitive data in the URI. Use a user-initiated print button.

## Known limitations of v0.1.0
- Generic TSPL coordinates are a first-pass layout; the unknown label-printer model must be confirmed before final calibration.
- Bluetooth Classic SPP only. Printers that use BLE or vendor-specific protocols need a separate transport implementation.
- ESC/POS receipt width, code page, feed and cutter behavior vary by printer. The current implementation uses conservative ASCII transliteration for Albanian diacritics and sends a cut command that printers without cutters should ignore.
- This is a starter implementation and has not yet been physically tested on the user's devices. A successful GitHub build and real printer output are separate verification steps.


## Label test profile v0.1.1
The test-label button prints `Trapano 750W Professional con percussione e valigetta`, SKU `TR-10025`, price `4,990 L`, and Code 128 data `123456789012`. Layout coordinates are an initial 203-dpi/8-dot-per-mm assumption and must be physically checked; adjust after seeing the real label output.
