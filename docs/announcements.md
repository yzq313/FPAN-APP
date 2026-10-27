# 管理 FPAN APP 公告

在仓库根目录编辑 `announcements.json`，提交到 `master` 分支。APP 通过 HTTPS 读取该文件；GitHub 缓存可能导致更新延迟。公告为公开数据，不要填写密钥或隐私信息。

`announcements` 是数组。每条公告的 `id` 必须非空且唯一；手机按 ID 记录已读，修改已有公告不会令其重新变为未读，需要再次提醒时请新建 ID。`title`、`summary`、`content` 分别是标题、摘要和正文，正文支持 Markdown。`publishAt` 使用 UTC 格式 `2026-10-27T00:00:00Z`；未来的时间不会提前显示。`createdAt` 和 `updatedAt` 建议使用同样的格式。`isPinned` 为 true 时置顶，可用整数 `sortOrder` 调整同组顺序。删除一项即可下架。

可选字段：`author`、`coverImage`、`images`（网址数组）、`publisher`（包含 `name` 和 `avatarUrl`）。图片应使用 HTTPS。静态文件不提供浏览量统计。保持 JSON 格式正确，否则公告加载失败。仓库改名、分支更名或改为私有后，需要同步修改 `AnnouncementApi.kt` 中的 `BASE_URL` 并重新发布 APP。
