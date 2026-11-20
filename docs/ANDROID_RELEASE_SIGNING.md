# Android Release 签名配置

本文档说明 Flow Android 工程如何配置正式 Release 签名。Gradle 配置只读取仓库外的本地属性文件，不把密码写入构建脚本。

## 1. 推荐存放位置

Keystore 和签名属性文件放在用户目录下，不进入 Git：

```text
~/.android/keystores/
├── flow-release.jks
└── flow-release.properties
```

Gradle 固定读取：

```text
~/.android/keystores/flow-release.properties
```

不要将 `.jks`、`.p12`、`.pem` 或包含密码的 properties 文件放进仓库。

## 2. 创建正式 Keystore

`keytool` 会以交互方式询问密码，避免把密码写进 shell 历史或构建脚本：

```bash
mkdir -p "$HOME/.android/keystores"
chmod 700 "$HOME/.android/keystores"

keytool -genkeypair -v \
  -keystore "$HOME/.android/keystores/flow-release.jks" \
  -alias flow-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

按提示填写密码和证书信息。`keyAlias` 必须与后续 `flow-release.properties` 中的值一致。建议使用独立的、可靠的密码管理器生成并保存密码，不要使用示例或弱密码。

## 3. properties 文件格式

在 `~/.android/keystores/flow-release.properties` 中填写（以下为占位示例，不要直接使用示例值作为真实密码）：

```properties
storeFile=/Users/<当前用户>/.android/keystores/flow-release.jks
storePassword=<STORE_PASSWORD>
keyAlias=flow-release
keyPassword=<KEY_PASSWORD>
```

说明：

- `storeFile` 推荐使用绝对路径。Gradle 也支持相对于 properties 文件所在目录的路径。
- 四个字段必须全部存在且非空。
- 密码只保存在这个仓库外的文件中。
- 不要提交、截图或发送这个文件。

## 4. 文件权限

签名文件应仅当前用户可读写：

```bash
chmod 600 "$HOME/.android/keystores/flow-release.jks"
chmod 600 "$HOME/.android/keystores/flow-release.properties"
chmod 700 "$HOME/.android/keystores"
```

不要把目录权限放开给其他用户。CI 环境中应使用受保护的 secret，而不是把文件提交到仓库。

## 5. 备份

正式 Keystore 是应用身份的一部分，必须安全备份：

- 至少保留两份离线/加密备份，放在不同位置。
- 备份应包含 `.jks` 和密码；密码与 Keystore 分开保存。
- 不要只依赖一台开发机或一个 CI runner。
- 如果使用 Google Play App Signing，仍需保护上传密钥；丢失上传密钥后需要通过 Play Console 的恢复流程处理。

## 6. 构建正式 APK

确保 properties 和 Keystore 已创建、字段完整，然后在工程中执行：

```bash
cd android
./gradlew :app:assembleRelease
```

已签名 APK 预期输出：

```text
android/app/build/outputs/apk/release/app-release.apk
```

如果需要 Google Play 的 AAB：

```bash
cd android
./gradlew :app:bundleRelease
```

预期输出：

```text
android/app/build/outputs/bundle/release/app-release.aab
```

构建脚本会：

- 从 `~/.android/keystores/flow-release.properties` 读取签名信息。
- 校验四个必需字段和 `storeFile` 是否存在。
- 配置完整且 Keystore 存在时，将 release buildType 绑定到正式签名。
- 配置缺失或不完整时，在请求 Release 任务时抛出清晰错误，避免产出看起来像正式包但实际未签名的 APK。
- 配置检查会确认四个字段、Keystore 路径、文件可读性；不会打开 Keystore 校验密码/alias，密码或 alias 错误会在实际签名阶段失败。
- 不影响 Debug 构建和普通 Gradle 配置。

本次任务未创建 Keystore、未执行 Release 构建、未生成 APK。

## 7. 验证 APK 签名

使用 Android SDK build-tools 中的 `apksigner`：

```bash
"$ANDROID_HOME/build-tools/33.0.2/apksigner" verify \
  --verbose --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

如果 `ANDROID_HOME` 未设置，可使用本机的 SDK 路径，例如 macOS 默认位置：

```bash
$HOME/Library/Android/sdk/build-tools/33.0.2/apksigner verify \
  --verbose --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

检查输出中的证书指纹和签名者信息，确认与正式 Keystore 一致。

## 8. 后续版本必须使用相同 Keystore 和 alias

Android 通过包名 + 签名证书识别应用更新。后续版本必须使用同一套正式 Keystore 和 alias，否则：

- 设备可能拒绝覆盖安装。
- Google Play 可能拒绝更新。
- 已安装用户无法通过正常升级路径获得新版本。

不要把“新建一个 Keystore”当作常规发布步骤。若密钥丢失，恢复路径取决于是否启用了 Google Play App Signing；没有可用密钥时，可能无法继续更新同一应用。

## 9. Keystore 丢失或泄露的风险

- **丢失**：无法再为同一 applicationId 生成可被现有用户或商店接受的更新，除非有可用的 Play App Signing/恢复机制。
- **泄露**：攻击者可能伪造更新、冒充应用签名，造成严重安全风险。
- **密码泄露**：即使 Keystore 文件未泄露，攻击者仍可能使用密钥。

如果怀疑泄露：

1. 立即停止使用该密钥发布。
2. 评估是否启用 Google Play App Signing 的密钥轮换/恢复流程。
3. 按安全流程生成新密钥，并接受旧版本无法直接升级的后果。
4. 不要自行删除或覆盖现有发布记录。

## 10. 本次配置的保护行为

- 本工程不包含 Keystore 或真实密码。
- `.gitignore` 已排除常见签名文件和本地签名属性文件。
- 普通 Debug 构建不读取、不依赖正式签名文件。
- 当请求 Release 构建但签名配置缺失、字段不完整或 Keystore 不存在时，Gradle 会阻止任务执行并给出配置路径提示。
- 错误信息不包含密码、keyPassword 或属性文件内容。
