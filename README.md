# X Helper

X（Twitter）账号运营助手：记录 X 的打开/关闭与使用时长，抓取主页粉丝 / 关注 / 帖子数快照，每晚 23:05 推送当日总结。数据只存本机，不申请 `INTERNET` 权限。

- 包名：`xyz.moshushi.app`
- 构建：手工管线（`kotlinc` + `d8` + `aapt2` + `zipalign` + `apksigner`），见 `build-apk.sh`
- 最低：`build-tools 34.0.0`、`platform android-34`、JDK 17、`kotlinc`（jvm-target 17）

## 构建前准备

把下面两个 jar 放到 `libs/`（Maven Central 可下，仓库里不含二进制）：

- `kotlinx-coroutines-core-jvm-1.8.1.jar`
- `kotlinx-coroutines-android-1.8.1.jar`

`~/android-sdk`、`~/jdk17`、`~/kotlin/kotlinc` 按 `build-apk.sh` 头部的路径准备好，然后：

```bash
./build-apk.sh
```

产物在 `out/`。debug 签名用的 `debug.keystore`（密码 `android`）也在仓库里，仅供自用测试。

## 版本规则

对外 `versionName` 从 1.x 起：大功能升 minor（如 1.5），小修升 patch（如 1.4.1)；`versionCode` 内部持续递增。
