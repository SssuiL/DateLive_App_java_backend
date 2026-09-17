# 漫聊 Java 后端

GitHub：[DateLive_App_java_backend](https://github.com/SssuiL/DateLive_App_java_backend)。后续迁移完成并验证后提交推送，工作规则见 AGENTS.md。

独立工程目录：`E:/Project/backend-java`。旧 Python/FastAPI 工程 `E:/python_workspace/DateLive_App` 仅作只读参考。

## 当前交付范围

已建立可运行的 Java 账号、社交、聊天与实时通信模块；全部后端重构仍在进行。

- Java 25 LTS、Spring Boot 4.1.1、Maven 3.9.16、PostgreSQL 17.10。
- 已配置 VS Code Oracle Java 插件的项目 JDK。
- 已实现 145 个 HTTP 入口（其中超级喜欢为已取消的 410 兼容入口）和 2 个 WebSocket 入口：账号、资料、通知、图片、后台审核、注销/恢复、用户搜索、好友和拉黑，以及聊天会话、文本/图片/语音/视频/文件消息和静态贴纸/动态 GIF、引用回复、已读，以及撤回/搜索/个人隐藏和清空。到期清理已实现并在隔离库测试，自动 Worker 已获授权开启，每分钟清理超过 45 天冷静期的注销账号；可用 JAVA_ACCOUNT_ERASURE_ENABLED=false 关闭（本地启动脚本默认开启，使用 -DisableAccountErasure 可关闭）；短信仍为开发模拟。
- 新增实时消息/通知、多端同步、断线恢复、ACK 送达回执、在线与输入状态；Flutter 协议适配和容量压测尚待完成。
- 独立 Flyway 账号数据基线、事务审计、密码哈希、数据库会话校验和多实例共享的数据库限流。
- 317 项测试覆盖真实 PostgreSQL/HTTP/WebSocket、并发、回滚、密码兼容、令牌签名与失效。
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

## 独立媒体 Worker

本地 start-local.ps1 现在默认启动 API 和无 HTTP 监听的独立媒体 Worker。Worker 单独限制 256 MiB Java 堆、2 个数据库连接；stop-local.ps1 停止两者，stop-media-worker-local.ps1 可只停止 Worker。可用 JAVA_MEDIA_JOBS_WORKER_ENABLED=false 暂停自动启动 Worker，排队数据仍保留。

全量 **297 项测试通过**，独立进程处理、无监听端口及停止 Worker 后 API 仍健康已实际验证：[本机报告](docs/local-media-worker-smoke.json)。堆上限不等于进程总内存或 CPU 硬配额；任务租约、OS 资源配额与容量测试仍待完成。

完整旧接口对照见 [全量迁移核对表](docs/全量迁移核对表.json)，由 tools/update-migration-ledger.ps1 更新。映射只证明存在路径，语义契约仍需逐项验收。

## 群组与普通喜欢（2026-09-17）

已完成群组治理、群聊和分享链接，以及无限普通喜欢、互相喜欢配对；超级喜欢按用户要求取消。详见 [群组与划卡迁移说明](docs/群组与划卡迁移说明.md)。
运行 tools/smoke-groups-explore-local.ps1 验证本地流程。测试域名由 JAVA_GROUP_PUBLIC_BASE_URL 或本地 groupPublicBaseUrl 配置；公网 DNS/TLS 与手机分享联调尚未完成。

## 动态与评论（V19）

已迁移 19 个动态接口，涵盖文字/图片/语音/视频、可见范围、点赞、线程评论、Emoji 点评、媒体保存偏好及审核联动。完整回归 297 项通过，本地 V19 动态 14 项和群组/划卡 21 项 HTTP 冒烟通过。详见 [迁移说明](docs/动态与评论迁移说明.md) 和 [本机报告](docs/local-posts-smoke.json)。

已使用现有 SSH 密钥完成轻量服务器只读盘点；尚未部署 Java 或修改云端服务。域名和实测资源见 [服务器接入记录](docs/轻量测试服务器接入记录.md)。全后端迁移继续进行，下一阶段为钱包账本与支付。

## 钱包账本（V20）

已迁移钱包六个接口及后台账本查询两个接口，包含幂等、收支平衡、历史流水不可变、并发扣款和注销留存。完整回归 **317 项通过**，本地 HTTP 12 项烟测通过：[迁移说明](docs/钱包账本迁移说明.md)、[验收报告](docs/local-wallet-smoke.json)。显式使用 -DevelopmentBilling 开启本地模拟金币充值；默认和生产环境关闭。支付订单、回调、礼物分账与通话扣费接入仍在后续迁移范围。

## 支付订单与回调（V21）

已迁移 8 个支付接口，覆盖订单幂等、签名回调、原子入账、并发防重及后台审计。完整回归 **346 项通过**，本地支付 12 项、钱包 12 项验证通过。微信支付官方 SDK 的 RSA/AES-GCM 及请求/响应签名已离线测试；真实商户、公网回调与 Flutter 支付联调未完成。详见 [迁移说明](docs/支付订单与回调迁移说明.md) 和 [本机报告](docs/local-payments-smoke.json)。

## 礼物目录与素材（V22）

累计 **358 项测试通过**，本地礼物目录 12 项验收通过。迁移默认礼物、上下架、管理员权限、图标/Lottie 上传、版本与校验和；新增公开素材入口。素材与目录、审计在同一数据库事务提交。详见 [迁移说明](docs/礼物目录迁移说明.md) 和 [本机报告](docs/local-gift-catalog-smoke.json)。送礼扣款、风控、分账、结算及 Flutter 动画联调仍在后续范围。
