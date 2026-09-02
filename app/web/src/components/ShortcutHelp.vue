<template>
  <div class="shortcut-help" v-if="visible" @click.self="$emit('close')">
    <div class="help-content">
      <div class="help-header">
        <Icon icon="mdi:keyboard" class="keyboard-icon" />
        <h3>快捷键帮助</h3>
        <button class="close-btn" @click="$emit('close')">
          <Icon icon="mdi:close" />
        </button>
      </div>

      <div class="help-body">
        <div class="shortcut-section">
          <h4>
            <Icon icon="mdi:pencil" />
            编辑器操作
          </h4>
          <div class="shortcut-list">
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>S</kbd>
              </span>
              <span class="shortcut-desc">保存草稿</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>Z</kbd>
              </span>
              <span class="shortcut-desc">撤销</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>Y</kbd>
              </span>
              <span class="shortcut-desc">重做</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>F</kbd>
              </span>
              <span class="shortcut-desc">查找</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>H</kbd>
              </span>
              <span class="shortcut-desc">替换</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Tab</kbd>
              </span>
              <span class="shortcut-desc">增加缩进</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Shift</kbd> + <kbd>Tab</kbd>
              </span>
              <span class="shortcut-desc">减少缩进</span>
            </div>
          </div>
        </div>

        <div class="shortcut-section">
          <h4>
            <Icon icon="mdi:play-circle" />
            代码执行
          </h4>
          <div class="shortcut-list">
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>Enter</kbd>
              </span>
              <span class="shortcut-desc">提交代码</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>Alt</kbd> + <kbd>R</kbd>
              </span>
              <span class="shortcut-desc">自测运行</span>
            </div>
          </div>
        </div>

        <div class="shortcut-section">
          <h4>
            <Icon icon="mdi:navigation" />
            导航操作
          </h4>
          <div class="shortcut-list">
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>[</kbd>
              </span>
              <span class="shortcut-desc">上一题</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>]</kbd>
              </span>
              <span class="shortcut-desc">下一题</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>L</kbd>
              </span>
              <span class="shortcut-desc">打开题目列表</span>
            </div>
          </div>
        </div>

        <div class="shortcut-section">
          <h4>
            <Icon icon="mdi:tools" />
            工具功能
          </h4>
          <div class="shortcut-list">
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>Alt</kbd> + <kbd>T</kbd>
              </span>
              <span class="shortcut-desc">插入代码模板</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Ctrl</kbd> + <kbd>/</kbd>
              </span>
              <span class="shortcut-desc">显示快捷键帮助</span>
            </div>
            <div class="shortcut-item">
              <span class="shortcut-keys">
                <kbd>Esc</kbd>
              </span>
              <span class="shortcut-desc">关闭弹窗/面板</span>
            </div>
          </div>
        </div>
      </div>

      <div class="help-footer">
        <Icon icon="mdi:information" />
        <p>提示：按 <kbd>Ctrl</kbd> + <kbd>/</kbd> 可随时打开此帮助</p>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  visible: Boolean
})

defineEmits(['close'])
</script>

<style scoped>
.shortcut-help {
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

.help-content {
  background: var(--panel);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 700px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.help-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
}

.keyboard-icon {
  font-size: 28px;
  color: var(--accent);
}

.help-header h3 {
  flex: 1;
  margin: 0;
  font-size: 20px;
  color: var(--text);
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
  border-radius: 8px;
}

.close-btn:hover {
  background: var(--bg);
  color: var(--text);
}

.help-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

.shortcut-section {
  margin-bottom: 28px;
}

.shortcut-section:last-child {
  margin-bottom: 0;
}

.shortcut-section h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 16px;
  color: var(--text);
  font-weight: 600;
}

.shortcut-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.shortcut-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--panel-2);
  border-radius: 8px;
  transition: all 0.15s ease;
}

.shortcut-item:hover {
  background: var(--bg);
  transform: translateX(4px);
}

.shortcut-keys {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

kbd {
  display: inline-block;
  padding: 4px 8px;
  background: var(--panel);
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  box-shadow: 0 2px 0 var(--border-strong);
  font-family: 'Segoe UI', system-ui, sans-serif;
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  line-height: 1;
  min-width: 24px;
  text-align: center;
}

.dark kbd {
  background: var(--panel);
  border-color: #3a4458;
  box-shadow: 0 2px 0 #3a4458;
}

.shortcut-desc {
  color: var(--muted);
  font-size: 14px;
}

.help-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}

.help-footer p {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 4px;
}

.help-footer kbd {
  font-size: 11px;
  padding: 2px 6px;
}

@media (max-width: 640px) {
  .shortcut-item {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  
  .shortcut-keys {
    flex-wrap: wrap;
  }
}
</style>
