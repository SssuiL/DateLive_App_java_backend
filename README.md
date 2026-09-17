# 漫聊 Java 后端

GitHub：[DateLive_App_java_backend](https://github.com/SssuiL/DateLive_App_java_backend)。后续迁移完成并验证后提交推送，工作规则见 AGENTS.md。

独立工程目录：`E:/Project/backend-java`。旧 Python/FastAPI 工程 `E:/python_workspace/DateLive_App` 仅作只读参考。

## 当前交付范围

已建立可运行的 Java 账号、社交、聊天与实时通信模块；全部后端重构仍在进行。

- Java 25 LTS、Spring Boot 4.1.1、Maven 3.9.16、PostgreSQL 17.10。
- 已配置 VS Code Oracle Java 插件的项目 JDK。
- 已实现 74 个 HTTP 接口和 2 个 WebSocket 入口：账号、资料、通知、图片、后台审核、注销/恢复、用户搜索、好友和拉黑，以及聊天会话、文本/图片/语音/视频/文件消息和静态贴纸/动态 GIF、引用回复、已读，以及撤回/搜索/个人隐藏和清空。到期清理已实现并在隔离库测试，自动 Worker 已获授权开启，每分钟清理超过 45 天冷静期的注销账号；可用 JAVA_ACCOUNT_ERASURE_ENABLED=false 关闭（本地启动脚本默认开启，使用 -DisableAccountErasure 可关闭）；短信仍为开发模拟。
- 新增实时消息/通知、多端同步、断线恢复、ACK 送达回执、在线与输入状态；Flutter 协议适配和容量压测尚待完成。
- 独立 Flyway 账号数据基线、事务审计、密码哈希、数据库会话校验和多实例共享的数据库限流。
- 232 项测试覆盖真实 PostgreSQL/HTTP/WebSocket、并发、回滚、密码兼容、令牌签名与失效。
- 本地 API 已启动于 `http://127.0.0.1:8200`；数据库只监听 `127.0.0.1:15433`。
- 后台页面、MFA、云存储、真实审核/短信供应商、推送投递、第三方登录和其他业务模块仍待迁移。

## 使用（PowerShell 7）

```powershell
# 在本项目根目录编译、测试、打包
.\tools\maven.ps1 verify

# 启动独立开发数据库和后台 API
.\tools\start-local.ps1

# 若要测试验证码流程，启动时改用：
.\tools\start-local.ps1 -DevelopmentSms
.\tools\smoke-sms-local.ps1

# 本机冒烟；会在 Java 独立数据库中创建 10 个测试账号
.\tools\smoke-local.ps1

# 只停止 Java API，保留独立数据库与数据
.\tools\stop-local.ps1
```

启动脚本自动为本地开发生成独立凭证，保存在被 Git 忽略的 `.tools/local-development.json`，不打印到终端。此文件需与本地数据库卷对应保留；丢失后需要单独恢复或重建开发环境，不能假定旧数据库密码随之改变。

本地脚本显式开启无短信测试注册，并仅绑定回环地址。默认应用配置关闭该入口及短信适配器；当前版本不作为正式上线版本使用。启动运行的是 `.tools/run/backend.jar` 副本，源码重新打包后需停止并重启本地 API 才加载新版本。

## 测试与运行隔离

- 集成测试由 Testcontainers 创建临时 PostgreSQL，完成后清理，不连接本地开发数据库或旧 Python 数据库。
- Java 源码、测试、依赖缓存、构建结果、部署配置与进度均在本目录。
- 不复用旧 `.env`、密钥、上传目录或数据库文件。
- PostgreSQL 镜像已按测试时的摘要固定；GitHub Actions 工作流已编写，尚未远程执行。
- 正常退出 API 不会删除 Docker 数据卷。不提供自动清空旧数据操作。

## 文档

- [重构进度与验收记录](docs/重构进度.md)
- [编译环境与编辑器配置](docs/编译环境.md)
- [完整重构实施方案](docs/重构实施方案.md)
- [Python 接口静态清单](docs/接口迁移清单.md)
- [静态盘点数据](docs/legacy-inventory.json)
- [独立实现状态](docs/migration-status.json)
- [本机账号采样报告](docs/local-auth-smoke.json)
- [验证码与设备流程冒烟报告](docs/local-sms-smoke.json)
- [资料与通知流程冒烟报告](docs/local-profile-notification-smoke.json)
- [图片上传与受控访问冒烟报告](docs/local-media-smoke.json)
- [后台身份与审核冒烟报告](docs/local-admin-smoke.json)
- [注销与恢复冒烟报告](docs/local-account-smoke.json)

静态盘点脚本 `python tools/inventory_legacy.py` 默认只读旧源码，可以通过 `--source` 指定隔离副本。它不代表运行时契约导出，也不覆盖独立进度文件。

- [社交接口契约与迁移边界](docs/社交接口迁移说明.md)
- [本地社交流程验证](docs/local-social-smoke.json)

- [聊天接口契约与迁移边界](docs/聊天接口迁移说明.md)
- [本地聊天流程验证](docs/local-chat-smoke.json)

- [消息交互契约与个人清空语义](docs/消息交互迁移说明.md)
- [本地消息交互验证](docs/local-chat-interaction-smoke.json)

- [实时通信契约与恢复协议](docs/实时通信迁移说明.md)
- [本地实时通信验证](docs/local-realtime-smoke.json)

- [聊天图片与静态贴纸迁移说明](docs/聊天图片迁移说明.md)
- [本地聊天图片验证](docs/local-chat-images-smoke.json)

- [语音消息、解码与播放说明](docs/语音消息迁移说明.md)
- [本地语音验证](docs/local-voice-smoke.json)

## 视频附件（V13）

已支持受控 MP4 视频、PNG 封面、签名变体续签与单段 Range；累计 199 项测试通过，本机视频冒烟通过。详见 [视频消息迁移说明](docs/视频消息迁移说明.md) 和 [视频冒烟报告](docs/local-video-smoke.json)。真机播放、异步队列、云存储及真实扫描/审核供应商待完成。

## GIF 与文件附件（V14）

已支持聊天动态 GIF 和普通文件，保留 GIF 帧/颜色/透明信息，普通文件强制附件下载；累计 199 项测试及本机附件冒烟通过。详见 [迁移说明](docs/GIF与文件附件迁移说明.md) 和 [本机报告](docs/local-attachments-smoke.json)。

## 图片衍生图（V15）

已支持 PNG/JPEG 缩略图与展示图、GIF 静态缩略图及动画展示、旧 Java 图片自动补生成，沿用签名访问与整套文件清理。完整回归 **214 项通过**，本机 V15 冒烟通过，自动注销清理保持开启。详见 [迁移说明](docs/图片衍生图迁移说明.md) 和 [本机报告](docs/local-derivatives-smoke.json)。新上传仍同步处理；通用异步队列和草稿回收待完成。

## 异步媒体与草稿回收（V16）

新增可选 POST /media/upload?async=true 与仅本人可用的 GET /media/{id}。有界 PostgreSQL 持久化队列支持图片、GIF、语音、视频和普通文件，提供重试、重启恢复与取消/注销清理。默认每分钟检查超过 24 小时且无消息或资料引用的草稿，锁内复查后回收。

完整回归 **232 项通过**，本机 V16 实际重启恢复冒烟通过。累计 **75 个 HTTP + 2 个 WebSocket**。Flutter 尚未切换异步调用；独立 Worker 进程、资源配额、云存储和真实扫描/审核仍待完成。详见 [接口与运行边界](docs/媒体异步与草稿回收迁移说明.md) 和 [本机报告](docs/local-media-jobs-smoke.json)。
