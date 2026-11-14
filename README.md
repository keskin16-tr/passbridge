# PassBridge Studio Pro Android 1.0.0

Kotlin + Jetpack Compose Android client for the supplied PassBridge Studio Pro Flask API (v7.0.0).

## Features
- Select `.pkpass` from Android Storage Access Framework.
- On-device ZIP / `pass.json` parsing with archive size limits.
- Apple pass type mapping: event ticket → `event`, boarding pass air → `flight`, store card → `loyalty`, coupon → `offer`, train/bus/boat → `transit`, generic → `generic`.
- Reads Apple header, primary, secondary, auxiliary and back fields.
- Extracts logo/icon and strip/background image for the local card preview.
- Edit issuer, title, color, field labels/values, Google Wallet role mappings and barcode value.
- Hybrid conversion through the existing Flask `/api/convert` endpoint.
- Configure API URL, optional API key, Google Wallet Issuer ID and upload Service Account JSON to the backend.
- GitHub Actions debug APK artifact workflow.

## Requirements
- Android Studio Ladybug or newer (or compatible Android Gradle Plugin 8.7.3).
- JDK 17.
- Android SDK Platform 35.
- Backend: your supplied `app.py` and `pass_parser.py`, deployed on a publicly reachable HTTPS server.

## Build locally
Open this folder in Android Studio and allow Gradle sync, then run `app`.
Or from the project root:

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
1. Create a GitHub repository and upload the contents of this folder to its root.
2. Push to `main` or `master`, or run **Actions → Android APK → Run workflow**.
3. Open the completed workflow run and download artifact `PassBridgeStudioPro-debug`.
4. Install `app-debug.apk` on Android (allow installation from the source you use).

## Backend deployment
Deploy the two supplied Python files together with the backend dependencies. Set environment variables on the server:

```env
HOST=0.0.0.0
PORT=3000
PUBLIC_BASE_URL=https://YOUR-DOMAIN
GOOGLE_WALLET_ISSUER_ID=YOUR_ISSUER_ID
API_KEY=CREATE_A_LONG_RANDOM_SECRET
WALLET_MODE=auto
```

Install Python dependencies (at minimum Flask, PyJWT with cryptography support, and Pillow for image processing). Keep the backend behind HTTPS. Configure your reverse proxy so the public HTTPS URL forwards to the Flask port.

The Android app expects these existing routes:
- `GET /api/config`
- `POST /api/config` (`issuerId`, `classSuffix`)
- `POST /api/credentials` (multipart field `file`)
- `POST /api/convert` (multipart field `overrides`)
- Google-hosted card media served from `/media/<filename>`

The service account private key is uploaded to the backend, never packaged in the APK. Do not expose the backend without HTTPS and a strong `API_KEY`. Restrict server access and rotate credentials if they are exposed.

## Important limitations
- This first Android client release performs local PKPASS parsing and preview. It does not verify Apple's PKPASS signature; the existing Python parser also reads pass contents rather than validating the signature.
- Google Wallet image URLs must be public HTTPS URLs. Set `PUBLIC_BASE_URL` to the externally reachable HTTPS base URL.
- The backend determines whether a particular Google Wallet object can be created based on the fields required by its `build_google()` implementation. For example, flight cards require a valid flight number, ISO departure time and 3-letter IATA origin/destination codes.
- The card preview is an approximate visual representation, not a pixel-identical rendering of Apple Wallet.
- The Android client sends the original PKPASS and edited overrides together to `/api/convert`, allowing the supplied backend to extract and process the original pass artwork. Google Wallet still requires the backend's `/media` image URLs to be publicly reachable over HTTPS.
