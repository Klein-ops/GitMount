# GitMount

把 GitHub 私有仓库挂载成本地 FTP / WebDAV 服务的 Android 应用。

在手机上启动服务后，同一局域网内的电脑、平板、NAS 即可用任意支持 FTP 或 WebDAV
的文件管理器访问该仓库：读取、上传、新建目录、重命名、删除，全部直接落到 GitHub 仓库，
不需要在本地维护一份同步副本。

## 特性

- **两种协议**：WebDAV（推荐，单端口、文件管理器兼容性好）与 FTP（PASV/EPSV 被动模式）。
- **私有仓库**：一键创建 `private` 仓库，数据不出自己的 GitHub 账号。
- **令牌加密**：GitHub Token 使用 Android Keystore 的 AES-256-GCM 加密后存储，
  明文不落盘，也不进版本库。
- **前台服务**：息屏、切后台仍保持服务可用，通知栏常驻状态与“停止”操作。
- **MD3 设计**：Material 3 组件与绿色调配色，支持深色模式。
- **零多余依赖**：FTP / WebDAV 服务端均为手写实现，只依赖 OkHttp、Compose、DataStore。

## 使用步骤

1. 在 GitHub 生成访问令牌
   - Classic token：勾选 `repo` 权限
   - Fine-grained token：授予目标仓库 `Contents` 的读写权限
2. 打开 App → 右上角设置 → 填入令牌与仓库名称 → 点“保存并初始化”
   - 仓库不存在会自动创建为私有仓库
   - 分支自动取仓库默认分支
3. 设置访问用的用户名与密码（仅用于局域网访问认证，可与令牌不同）
4. 返回主界面 → 启动服务 → 记下显示的 `http://手机IP:端口/`
5. 在电脑上添加该地址（WebDAV）或 `ftp://手机IP:端口/`（FTP），用第 3 步的账号登录

## 技术实现要点

| 关注点 | 处理方式 |
| --- | --- |
| 存储后端 | GitHub Contents API（单文件 base64） |
| 大文件读取 | Contents API 对 >1MB 返回 `encoding=none`，自动回退 Git Blob API |
| 空目录 | GitHub 无空目录概念，用 `.gitkeep` 占位，对客户端隐藏 |
| 目录刷新 | 4 秒 TTL 缓存，避免文件管理器高频刷新耗尽 API 配额 |
| 写入冲突 | 覆盖前取最新 `sha` 做乐观锁，避免 409 |
| 路径安全 | `..` 折叠在根目录处截断，无法越出仓库根 |
| 删除目录 | 递归删除子项与占位文件 |
| 移动/复制 | GitHub 无 move，走“读 → 写 → 删” |

## 已知限制

这些是平台或协议本身的边界，不是缺陷：

1. **每次写入都会产生一次 commit**。GitHub 仓库本质是 git 仓库，Contents API 写入必然提交。
   App 内已自动生成提交信息，用户无需关心。
2. **单文件建议不超过 95MB**。Contents API 对更大文件支持不稳定，超出会被明确拒绝并提示。
3. **API 配额**：认证后 5000 次/小时。大量小文件读写消耗较快，目录缓存已做缓解。
4. **默认仅局域网可达**。公网访问需要路由器端口映射或内网穿透，不在应用职责内。
5. **FTP 为明文传输**，适合可信局域网；对安全性要求高时请用 WebDAV（同样为明文 HTTP，
   如需加密请置于反向代理之后）。
6. **无协议级加锁语义**。LOCK 返回乐观锁令牌，未实现真正的锁冲突拒绝，
   多客户端同时写同一文件时后写覆盖先写。

## 构建

```bash
# 需要 JDK 17、Android SDK 34
./gradlew assembleDebug
```

工程已在 `app/build.gradle.kts` 固定：AGP 8.5.2、Kotlin 1.9.24、Compose Compiler 1.5.14、
minSdk 26、targetSdk 34。

## 目录结构

```
app/src/main/java/com/kleinops/gitmount/
├── MainActivity.kt                  入口，通知权限申请
├── data/
│   ├── Model.kt                     ServerMode / RepoConfig / VfsNode 等模型
│   ├── SettingsRepository.kt        DataStore 配置 + Keystore 令牌加密
│   ├── GitHubClient.kt              GitHub REST 客户端
│   └── GitRepository.kt             虚拟文件系统（协议无关的读写语义）
├── service/
│   ├── http/HttpServer.kt           轻量 HTTP/1.1 服务器
│   ├── WebDavHandler.kt             WebDAV 协议处理
│   ├── FtpServer.kt                 FTP 协议处理
│   ├── MountService.kt              前台服务与通知
│   ├── ServerController.kt          运行状态总线 + 日志总线
│   └── NetUtils.kt                  局域网 IP 探测
└── ui/
    ├── MainViewModel.kt             配置校验与初始化流程
    ├── MainScreen.kt                MD3 主界面与配置对话框
    ├── components/Common.kt         复用组件
    └── theme/                       MD3 主题与配色
```

## 安全说明

- 令牌使用 Android Keystore 硬件级密钥加密，仅在应用进程内解密使用。
- 访问密码以明文存于应用私有的 DataStore（不参与备份，`allowBackup=false`）。
- 应用不收集、不上传任何数据，所有流量仅指向 `api.github.com`。
