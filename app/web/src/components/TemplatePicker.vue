<template>
  <div class="template-picker" v-if="visible" @click.self="$emit('close')">
    <div class="picker-content">
      <div class="picker-header">
        <Icon icon="mdi:code-braces" />
        <h3>代码模板</h3>
        <button class="close-btn" @click="$emit('close')">
          <Icon icon="mdi:close" />
        </button>
      </div>

      <div class="picker-body">
        <div class="tabs">
          <button 
            class="tab-btn" 
            :class="{ active: activeTab === 'basic' }" 
            @click="activeTab = 'basic'">
            <Icon icon="mdi:file-document" />
            基础模板
          </button>
          <button 
            class="tab-btn" 
            :class="{ active: activeTab === 'snippets' }" 
            @click="activeTab = 'snippets'">
            <Icon icon="mdi:puzzle" />
            代码片段
          </button>
        </div>

        <div v-if="activeTab === 'basic'" class="template-section">
          <div class="template-card" @click="insertBasicTemplate">
            <div class="card-header">
              <Icon icon="mdi:file-code" class="card-icon" />
              <div class="card-info">
                <h4>{{ basicTemplate.name }}</h4>
                <p>标准输入输出模板，适合快速开始</p>
              </div>
            </div>
            <div class="card-preview">
              <pre>{{ basicTemplate.code.substring(0, 150) }}...</pre>
            </div>
            <div class="card-action">
              <Icon icon="mdi:plus-circle" />
              插入编辑器
            </div>
          </div>
        </div>

        <div v-else class="snippets-section">
          <div v-if="snippets.length === 0" class="empty-snippets">
            <Icon icon="mdi:information" />
            <p>该语言暂无代码片段</p>
          </div>

          <div v-else class="snippet-list">
            <div 
              v-for="(snippet, idx) in snippets" 
              :key="idx"
              class="snippet-card"
              @click="insertSnippet(snippet)">
              <div class="snippet-header">
                <div class="snippet-icon">
                  <Icon icon="mdi:code-tags" />
                </div>
                <div class="snippet-info">
                  <h4>{{ snippet.name }}</h4>
                  <p>{{ snippet.desc }}</p>
                </div>
              </div>
              <div class="snippet-preview">
                <pre>{{ snippet.code }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useCodeTemplates } from '../composables/useCodeTemplates'

const props = defineProps({
  visible: Boolean,
  language: String
})

const emit = defineEmits(['close', 'insert'])

const activeTab = ref('basic')
const { getBasicTemplate, getSnippets } = useCodeTemplates()

const basicTemplate = computed(() => getBasicTemplate(props.language))
const snippets = computed(() => getSnippets(props.language))

function insertBasicTemplate() {
  emit('insert', basicTemplate.value.code)
  emit('close')
}

function insertSnippet(snippet) {
  emit('insert', snippet.code)
  emit('close')
}
</script>

<style scoped>
.template-picker {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.picker-content {
  background: var(--panel);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 800px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.picker-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
}

.picker-header h3 {
  flex: 1;
  margin: 0;
  font-size: 18px;
}

.close-btn {
  width: 36px;
  height: 36px;
  padding: 0;
  display: grid;
  place-items: center;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
}

.picker-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--muted);
  font-weight: 500;
}

.tab-btn.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.template-card, .snippet-card {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.15s ease;
  margin-bottom: 12px;
}

.template-card:hover, .snippet-card:hover {
  background: var(--bg);
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.15);
}

.card-header, .snippet-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.card-icon {
  font-size: 32px;
  color: var(--accent);
}

.snippet-icon {
  width: 40px;
  height: 40px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 20px;
}

.card-info, .snippet-info {
  flex: 1;
}

.card-info h4, .snippet-info h4 {
  margin: 0 0 4px;
  font-size: 16px;
  color: var(--text);
}

.card-info p, .snippet-info p {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

.card-preview, .snippet-preview {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  overflow: hidden;
}

.card-preview pre, .snippet-preview pre {
  margin: 0;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-all;
}

.card-action {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: var(--accent);
  color: #fff;
  border-radius: 6px;
  font-weight: 600;
  font-size: 14px;
}

.empty-snippets {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 20px;
  color: var(--muted);
}

.empty-snippets svg {
  font-size: 48px;
  opacity: 0.5;
}
</style>
