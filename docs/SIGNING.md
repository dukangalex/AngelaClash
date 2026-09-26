# Angela Clash 签名

`io.chainbox.clash` 的升级身份是这一把正式钥匙，不是调试证书。装过正式包的用户，之后只能用同一把钥匙升级。换钥匙等于换一个新应用，必须卸载重装。

| 项 | 值 |
|---|---|
| 别名 | `angelaclash` |
| 类型 | PKCS12，RSA 2048 |
| 证书 SHA-256 | `82:D0:DB:00:7C:5E:AF:C3:7E:12:6B:4F:DC:16:66:0D:7C:F2:43:4C:7A:15:E6:10:F4:B4:3F:D6:42:69:43:DC` |
| 主体 | `CN=Angela Clash, O=Angela Clash, C=US` |
| 有效期 | 10000 天 |

上游仓库自带的 `release.keystore` 已经从本分支拿掉。那不是 Angela Clash 的钥匙，不要再加回来。

## 不要提交

`.gitignore` 已经挡住这些文件：

- `release.keystore`
- `*.jks`
- `signing.properties`

仓库里只留 `signing.properties.example`。密码和钥匙只放在自己手里，以及 GitHub Actions secrets。

## 本机正式包

```properties
keystore.password=...
key.alias=angelaclash
key.password=...
```

把这份内容写成项目根目录的 `signing.properties`，钥匙文件放成 `release.keystore`，然后：

```bash
./gradlew app:assembleMetaRelease
```

没有这两份文件时，带 `Release` 的任务会直接失败，不会退回调试签名。`assembleDebug` 仍然用调试证书。

## Actions secrets

| Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的一整行 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_PASSWORD` | 密钥密码（与上面相同） |
| `SIGNING_KEY_ALIAS` | `angelaclash` |

`Build Release`、`Build Pre-Release`、`Build Debug` 都会先写出钥匙再编 Release。日志里不再打印 `signing.properties`。
