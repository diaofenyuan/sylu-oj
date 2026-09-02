<template>
  <div class="discussion-zone">
    <div class="discussion-header">
      <h3>
        <Icon icon="mdi:comment-multiple" />
        题目讨论
        <span class="count">{{ discussions.length }} 条</span>
      </h3>
      <button @click="showEditor = !showEditor" class="btn-primary">
        <Icon :icon="showEditor ? 'mdi:close' : 'mdi:plus'" />
        {{ showEditor ? '取消' : '发表讨论' }}
      </button>
    </div>

    <!-- 发表讨论编辑器 -->
    <div v-if="showEditor" class="editor-card">
      <div class="editor-tabs">
        <button 
          :class="{ active: editorTab === 'edit' }" 
          @click="editorTab = 'edit'">
          <Icon icon="mdi:pencil" />
          编辑
        </button>
        <button 
          :class="{ active: editorTab === 'preview' }" 
          @click="editorTab = 'preview'">
          <Icon icon="mdi:eye" />
          预览
        </button>
      </div>

      <div v-show="editorTab === 'edit'" class="editor-body">
        <input 
          v-model="newTitle" 
          placeholder="标题（可选）" 
          class="title-input"
        />
        <textarea 
          v-model="newContent" 
          placeholder="支持 Markdown 语法...&#10;&#10;示例：&#10;```cpp&#10;代码块&#10;```" 
          rows="8"
          class="content-textarea"
        ></textarea>
        <div class="editor-toolbar">
          <div class="toolbar-left">
            <button @click="insertMarkdown('**', '**')" title="粗体">
              <Icon icon="mdi:format-bold" />
            </button>
            <button @click="insertMarkdown('*', '*')" title="斜体">
              <Icon icon="mdi:format-italic" />
            </button>
            <button @click="insertMarkdown('`', '`')" title="行内代码">
              <Icon icon="mdi:code-tags" />
            </button>
            <button @click="insertMarkdown('```\n', '\n```')" title="代码块">
              <Icon icon="mdi:code-braces" />
            </button>
          </div>
          <button @click="submitDiscussion" class="btn-submit">
            <Icon icon="mdi:send" />
            发表
          </button>
        </div>
      </div>

      <div v-show="editorTab === 'preview'" class="preview-body">
        <div v-if="newContent" class="markdown-content" v-html="renderMarkdown(newContent)"></div>
        <div v-else class="empty-preview">
          <Icon icon="mdi:information" />
          暂无内容
        </div>
      </div>
    </div>

    <!-- 筛选和排序 -->
    <div class="filter-bar">
      <div class="filter-tabs">
        <button 
          :class="{ active: filter === 'all' }" 
          @click="filter = 'all'">
          全部
        </button>
        <button 
          :class="{ active: filter === 'official' }" 
          @click="filter = 'official'">
          <Icon icon="mdi:check-decagram" />
          官方题解
        </button>
        <button 
          :class="{ active: filter === 'mine' }" 
          @click="filter = 'mine'">
          我的发表
        </button>
      </div>
      <select v-model="sortBy" class="sort-select">
        <option value="latest">最新发表</option>
        <option value="popular">最多点赞</option>
        <option value="replies">最多回复</option>
      </select>
    </div>

    <!-- 讨论列表 -->
    <div class="discussion-list">
      <div 
        v-for="disc in filteredDiscussions" 
        :key="disc.id"
        class="discussion-card"
        :class="{ official: disc.isOfficial, pinned: disc.isPinned }">
        
        <div class="disc-header">
          <div class="author-info">
            <div class="avatar">
              {{ disc.authorName.charAt(0) }}
            </div>
            <div class="author-detail">
              <div class="author-name">
                {{ disc.authorName }}
                <span v-if="disc.isTeacher" class="badge badge-teacher">
                  <Icon icon="mdi:school" />
                  教师
                </span>
                <span v-if="disc.isOfficial" class="badge badge-official">
                  <Icon icon="mdi:check-decagram" />
                  官方题解
                </span>
              </div>
              <div class="disc-meta">
                <span>{{ formatTime(disc.createdAt) }}</span>
                <span v-if="disc.isPinned" class="pinned-tag">
                  <Icon icon="mdi:pin" />
                  置顶
                </span>
              </div>
            </div>
          </div>
          <div v-if="canManage(disc)" class="disc-actions">
            <button @click="togglePin(disc)" title="置顶">
              <Icon :icon="disc.isPinned ? 'mdi:pin-off' : 'mdi:pin'" />
            </button>
            <button @click="markOfficial(disc)" title="官方题解">
              <Icon icon="mdi:check-decagram" />
            </button>
            <button @click="deleteDiscussion(disc)" class="btn-danger" title="删除">
              <Icon icon="mdi:delete" />
            </button>
          </div>
        </div>

        <div class="disc-body">
          <h4 v-if="disc.title" class="disc-title">{{ disc.title }}</h4>
          <div class="disc-content markdown-content" v-html="renderMarkdown(disc.content)"></div>
        </div>

        <div class="disc-footer">
          <button 
            @click="toggleLike(disc)" 
            :class="{ liked: disc.isLiked }"
            class="action-btn">
            <Icon :icon="disc.isLiked ? 'mdi:thumb-up' : 'mdi:thumb-up-outline'" />
            {{ disc.likes }}
          </button>
          <button 
            @click="toggleReplies(disc)" 
            class="action-btn">
            <Icon icon="mdi:comment-outline" />
            {{ disc.replies.length }} 回复
          </button>
        </div>

        <!-- 回复列表 -->
        <div v-if="disc.showReplies" class="replies-section">
          <div v-for="reply in disc.replies" :key="reply.id" class="reply-card">
            <div class="reply-header">
              <div class="avatar small">{{ reply.authorName.charAt(0) }}</div>
              <div class="reply-info">
                <strong>{{ reply.authorName }}</strong>
                <span class="reply-meta">{{ formatTime(reply.createdAt) }}</span>
              </div>
            </div>
            <div class="reply-content">{{ reply.content }}</div>
          </div>

          <div class="reply-editor">
            <textarea 
              v-model="disc.replyText" 
              placeholder="写下你的回复..."
              rows="3"
            ></textarea>
            <button @click="submitReply(disc)" class="btn-reply">
              <Icon icon="mdi:send" />
              回复
            </button>
          </div>
        </div>
      </div>

      <div v-if="!filteredDiscussions.length" class="empty-discussions">
        <Icon icon="mdi:comment-off-outline" />
        <p>还没有讨论，来发表第一条吧</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  problemId: {
    type: Number,
    required: true
  },
  userRole: {
    type: String,
    default: 'student' // student | teacher
  },
  userId: {
    type: Number,
    required: true
  }
})

const showEditor = ref(false)
const editorTab = ref('edit')
const newTitle = ref('')
const newContent = ref('')
const filter = ref('all')
const sortBy = ref('latest')

// 模拟数据（实际应该从API获取）
const discussions = ref([
  {
    id: 1,
    authorName: '张老师',
    authorId: 100,
    isTeacher: true,
    isOfficial: true,
    isPinned: true,
    title: '官方题解：动态规划解法',
    content: '这道题可以使用动态规划来解决...\n\n```cpp\nint dp[1000];\nfor (int i = 0; i < n; i++) {\n    dp[i] = max(dp[i-1], dp[i-2] + arr[i]);\n}\n```',
    likes: 45,
    isLiked: false,
    createdAt: '2026-09-01T10:00:00Z',
    replies: [
      {
        id: 101,
        authorName: '学生A',
        authorId: 200,
        content: '感谢老师的详细讲解！',
        createdAt: '2026-09-01T11:00:00Z'
      }
    ],
    showReplies: false,
    replyText: ''
  },
  {
    id: 2,
    authorName: '学生B',
    authorId: 201,
    isTeacher: false,
    isOfficial: false,
    isPinned: false,
    title: '贪心算法也可以AC',
    content: '我用贪心算法通过了这道题，时间复杂度是 O(n log n)',
    likes: 12,
    isLiked: true,
    createdAt: '2026-09-02T08:30:00Z',
    replies: [],
    showReplies: false,
    replyText: ''
  }
])

const filteredDiscussions = computed(() => {
  let result = discussions.value

  // 筛选
  if (filter.value === 'official') {
    result = result.filter(d => d.isOfficial)
  } else if (filter.value === 'mine') {
    result = result.filter(d => d.authorId === props.userId)
  }

  // 排序
  result = [...result].sort((a, b) => {
    if (a.isPinned !== b.isPinned) return b.isPinned - a.isPinned
    
    if (sortBy.value === 'popular') {
      return b.likes - a.likes
    } else if (sortBy.value === 'replies') {
      return b.replies.length - a.replies.length
    } else {
      return new Date(b.createdAt) - new Date(a.createdAt)
    }
  })

  return result
})

function renderMarkdown(text) {
  if (!text) return ''
  const html = marked(text)
  return DOMPurify.sanitize(html)
}

function formatTime(time) {
  const date = new Date(time)
  const now = new Date()
  const diff = Math.floor((now - date) / 1000)
  
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 604800) return `${Math.floor(diff / 86400)} 天前`
  
  return date.toLocaleDateString('zh-CN')
}

function insertMarkdown(before, after) {
  const textarea = document.querySelector('.content-textarea')
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const text = newContent.value
  
  newContent.value = text.substring(0, start) + before + text.substring(start, end) + after + text.substring(end)
  
  setTimeout(() => {
    textarea.focus()
    textarea.setSelectionRange(start + before.length, end + before.length)
  }, 0)
}

function submitDiscussion() {
  if (!newContent.value.trim()) return
  
  const newDisc = {
    id: Date.now(),
    authorName: '当前用户',
    authorId: props.userId,
    isTeacher: props.userRole === 'teacher',
    isOfficial: false,
    isPinned: false,
    title: newTitle.value,
    content: newContent.value,
    likes: 0,
    isLiked: false,
    createdAt: new Date().toISOString(),
    replies: [],
    showReplies: false,
    replyText: ''
  }
  
  discussions.value.unshift(newDisc)
  newTitle.value = ''
  newContent.value = ''
  showEditor.value = false
}

function toggleLike(disc) {
  disc.isLiked = !disc.isLiked
  disc.likes += disc.isLiked ? 1 : -1
}

function toggleReplies(disc) {
  disc.showReplies = !disc.showReplies
}

function submitReply(disc) {
  if (!disc.replyText.trim()) return
  
  const newReply = {
    id: Date.now(),
    authorName: '当前用户',
    authorId: props.userId,
    content: disc.replyText,
    createdAt: new Date().toISOString()
  }
  
  disc.replies.push(newReply)
  disc.replyText = ''
}

function canManage(disc) {
  return props.userRole === 'teacher' || disc.authorId === props.userId
}

function togglePin(disc) {
  if (props.userRole !== 'teacher') return
  disc.isPinned = !disc.isPinned
}

function markOfficial(disc) {
  if (props.userRole !== 'teacher') return
  disc.isOfficial = !disc.isOfficial
}

function deleteDiscussion(disc) {
  if (!confirm('确定要删除这条讨论吗？')) return
  const index = discussions.value.findIndex(d => d.id === disc.id)
  if (index !== -1) discussions.value.splice(index, 1)
}
</script>

<style scoped>
.discussion-zone {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.discussion-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.discussion-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.count {
  font-size: 14px;
  color: var(--muted);
  font-weight: 400;
}

/* 编辑器卡片 */
.editor-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.editor-tabs {
  display: flex;
  border-bottom: 1px solid var(--border);
}

.editor-tabs button {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px;
  background: var(--panel-2);
  border: none;
  color: var(--muted);
  font-weight: 500;
}

.editor-tabs button.active {
  background: var(--panel);
  color: var(--accent);
  border-bottom: 2px solid var(--accent);
}

.editor-body, .preview-body {
  padding: 16px;
}

.title-input {
  width: 100%;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
}

.content-textarea {
  width: 100%;
  padding: 12px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-family: 'Cascadia Code', monospace;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
}

.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.toolbar-left {
  display: flex;
  gap: 4px;
}

.toolbar-left button {
  width: 36px;
  height: 36px;
  padding: 0;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
}

.toolbar-left button:hover {
  background: var(--bg);
  color: var(--accent);
}

.btn-submit {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--accent);
  color: #fff;
}

.preview-body {
  min-height: 200px;
}

.empty-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 60px 20px;
  color: var(--muted);
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.filter-tabs {
  display: flex;
  gap: 8px;
}

.filter-tabs button {
  padding: 6px 12px;
  background: transparent;
  border: 1px solid var(--border);
  color: var(--muted);
  font-size: 13px;
}

.filter-tabs button.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.sort-select {
  padding: 6px 12px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
}

/* 讨论列表 */
.discussion-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.discussion-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  transition: all 0.2s ease;
}

.discussion-card:hover {
  box-shadow: var(--shadow-md);
}

.discussion-card.official {
  border-color: var(--accent);
  background: linear-gradient(to right, var(--panel), var(--accent-soft));
}

.discussion-card.pinned {
  border-color: var(--warn);
}

.disc-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}

.author-info {
  display: flex;
  gap: 12px;
}

.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 16px;
}

.avatar.small {
  width: 32px;
  height: 32px;
  font-size: 14px;
}

.author-detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.author-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--text);
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.badge-teacher {
  background: var(--warn-soft);
  color: var(--warn);
}

.badge-official {
  background: var(--accent-soft);
  color: var(--accent);
}

.disc-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--muted);
}

.pinned-tag {
  display: flex;
  align-items: center;
  gap: 3px;
  color: var(--warn);
}

.disc-actions {
  display: flex;
  gap: 4px;
}

.disc-actions button {
  width: 32px;
  height: 32px;
  padding: 0;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
}

.disc-actions .btn-danger {
  color: var(--danger);
}

.disc-body {
  margin-bottom: 16px;
}

.disc-title {
  margin: 0 0 12px;
  font-size: 18px;
  color: var(--text);
}

.disc-content {
  color: var(--text);
  line-height: 1.7;
}

.markdown-content :deep(pre) {
  background: var(--panel-2);
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
}

.markdown-content :deep(code) {
  font-family: 'Cascadia Code', monospace;
  font-size: 13px;
}

.disc-footer {
  display: flex;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
  font-size: 13px;
}

.action-btn.liked {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
}

/* 回复区域 */
.replies-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.reply-card {
  padding: 12px;
  background: var(--panel-2);
  border-radius: 8px;
  margin-bottom: 8px;
}

.reply-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.reply-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.reply-meta {
  color: var(--muted);
  font-size: 12px;
}

.reply-content {
  font-size: 14px;
  color: var(--text);
  line-height: 1.6;
}

.reply-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.reply-editor textarea {
  width: 100%;
  padding: 10px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 14px;
  resize: vertical;
}

.btn-reply {
  align-self: flex-end;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--accent);
  color: #fff;
}

.empty-discussions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px 20px;
  color: var(--muted);
  text-align: center;
}

.empty-discussions svg {
  font-size: 64px;
  opacity: 0.3;
}

@media (max-width: 768px) {
  .filter-bar {
    flex-direction: column;
    gap: 12px;
  }
  
  .filter-tabs {
    width: 100%;
  }
  
  .filter-tabs button {
    flex: 1;
  }
}
</style>
