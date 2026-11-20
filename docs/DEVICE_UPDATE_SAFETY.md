# Debug APK update safety

The first delivered test APK was main c67cd1c, Actions run 42981822835.
Its signing certificate SHA-256 is:
`cebfb29e3a416de0dbf71ce76eec3f5a08fec31b5f05f72d2235c62532d1cff4`

The later main f81c9d8 APK from run 42991179491 has a different certificate:
`c000cc55ae7ce4033f7f992a5fec9b686989625fc0d519fe1004ca19780879b7`

Both were checked using apksigner against the downloaded APKs. Android will
not accept a different signing certificate as an in-place update. The current
CI creates a debug key on each fresh runner and has no key-backup step. APKs
contain only the certificate, not a recoverable copy of the private key.

Do not advise uninstalling or clearing data to get past this. That removes
app-private data and the Android Keystore keys protecting provider credentials.
A database-only backup cannot restore those keys.

Before a replacement APK is presented as an update:

1. Recover the original private signing key if any external backup exists.
2. Otherwise, ask the owner to choose the delivery/migration route. A separate
   preview application can coexist with the original, but it needs its own
   provider setup and permissions. Do not silently change applicationId.
3. Set up one stable signing identity in protected CI secrets, outside source
   control. Do not put a keystore, password, base64 private key, or signing token
   into commits, logs, attachments, or a public release. Do not print it.
4. Compare the candidate's signing certificate against the installed-build
   certificate before claiming it is update-compatible.

Recovery check Nov 11: the original run's artifacts include APK, lint and
corpus reports, not a key backup; no Actions cache was returned; no keystore/JKS
appeared in repository history. Live GitHub settings also showed no repository
or environment secrets. The original CI key has not been recovered. A backup
outside these checked sources remains possible, but none is known here.

The API token cannot manage Actions secrets (403), but the authenticated owner
session could. A stable **test-only debug identity** has now been stored in the
encrypted repository secret `RAIJIN_DEBUG_KEYSTORE_B64`, using the existing local
build's private debug keystore. No private key was committed or printed. The
workflow restores it before building and checks this public certificate digest:
`028400915641ee795441bc5dd3cac6ab7045ecf0cd13f0f79d76434dce104bd2`

This prevents future identity drift; it does **not** make it compatible with the
already-installed c67cd1c build. The app ID is unchanged. Do not present this APK
as an in-place update to that build. The owner still must choose a migration or
separate-preview route, or supply an original-key backup securely if one exists.
This debug key is not an app-store production signing key.

Kokoro model bundling is deferred by owner request. This patch keeps existing
provider data/settings formats and database versions unchanged.
