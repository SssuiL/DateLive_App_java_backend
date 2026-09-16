# GIF 与普通文件附件迁移说明

工程：E:/Project/backend-java；V14；复用现有媒体与聊天接口。

## GIF

- 上传 media_type=image、source=chat、conversation_id，MIME 为 image/gif。
- 发送 type=image、media_kind=gif、media_asset_id；不将 GIF 改成独立消息类型。
- 逐帧解码并重新编码 GIF，保留画布、帧位置、调色板、透明信息、帧处置方式、循环次数；移除注释和非必要应用扩展。
- 过短帧延时规范为至少 20ms；无循环扩展时不额外添加循环。
- 输入最多 8 MiB，画布最多 100 万像素、120 帧，画布像素乘帧数最多 1600 万；一轮最多 60 秒；输出最多 16 MiB。
- MIME 与真实格式不匹配、损坏、超限会被拒绝；本轮仅允许聊天 GIF，资料头像/相册继续 PNG/JPEG。
- 回复、历史、搜索、实时投递、幂等、审核及权限沿用 image 附件；GIF 标签不可用于非 GIF 媒体。

## 普通文件

- 上传 media_type=file、source=chat、conversation_id；发送 type=file、media_asset_id，media_kind 为空，duration_seconds 为空或 0。
- 非空且最多 32 MiB，原始字节与 SHA256 保留；存储名由服务端生成。
- 不解压、不执行、不渲染文件。下载统一 application/octet-stream、Content-Disposition: attachment、nosniff，保留清理后的原始文件名。
- 用户和管理员受控下载均强制附件方式，并支持已有单段 Range。
- 人工批准后才能发送。malware_status=skipped，表示本轮尚未接入病毒扫描，不能视为通过安全扫描。

## 共用行为与验证

- 要求上传者是当前好友会话成员；附件仅能绑定一个消息，不得跨会话或冒用他人附件。
- 发送、媒体绑定、未读、回执、通知和 outbox 同事务；并发重试仅产生一次消息。
- 每次下载复核登录会话、消息可见性、审核和业务权限；隐藏、撤回等撤销相应旧链接。
- 删除及到期注销复用现有媒体清理；自动到期 Worker 保持开启。
- AttachmentIntegrationTests 使用合成 GIF/文本文件，覆盖颜色、透明像素、帧/延时/循环、格式/限额拒绝、文件字节及下载头、HTTP/WebSocket、权限、审核、幂等、回滚、删除和注销。
- tools/smoke-attachments-local.ps1 验证本机 V14；报告 docs/local-attachments-smoke.json。
- 全量验证与部署记录见重构进度；Flutter 真机显示/文件保存未验收。

## 后续

继续异步媒体处理、草稿回收、衍生图、云存储和真实扫描/审核接入，再进入群聊与探索/动态、钱包支付、直播与通话及全量验收。

## GitHub

用户指定远端：https://github.com/SssuiL/DateLive_App_java_backend.git。
源码、迁移、测试、工具脚本与进度文档纳入版本控制；本机凭证、.tools、.data、target、堆转储等不提交。后续完成验证后提交推送，不强制覆盖远端。
