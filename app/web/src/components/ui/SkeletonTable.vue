<template>
  <!--
    无障碍播报与 aria-busy 放在 wrapper 上，不能放在 <table> 内部：
    表格只允许 caption/colgroup/thead/tbody/tfoot/tr 等子元素，
    放入 <span> 会被浏览器的 foster parenting 提升到表格之外，
    造成真实 DOM 与虚拟 DOM 不一致。
  -->
  <div class="skeleton-table" role="status" aria-busy="true" aria-live="polite">
    <table>
      <thead>
        <tr>
          <th v-for="c in cols" :key="`h${c}`">
            <span class="skeleton sk-line" :style="{ '--skeleton-w': headerWidth(c) }"></span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="`r${r}`">
          <td v-for="c in cols" :key="`c${r}-${c}`">
            <span class="skeleton sk-line" :style="{ '--skeleton-w': cellWidth(r, c) }"></span>
          </td>
        </tr>
      </tbody>
    </table>
    <span class="sr-only">表格加载中</span>
  </div>
</template>

<script setup>
/**
 * SkeletonTable — 表格加载骨架
 * -----------------------------------------------------------------------------
 * 直接渲染真实的 <table> 结构并复用全局表格样式，使表头高度、单元格内边距、
 * 行分隔线与真实表格一致 —— 这是避免"数据到达时表格跳动"的关键。
 * 若改用 div 拼形状，很难与真实表格的尺寸对齐。
 *
 * 用法：
 *   <SkeletonTable :rows="5" :cols="6" />
 */
defineProps({
  rows: { type: Number, default: 4 },
  cols: { type: Number, default: 4 }
})

// 表头占位略宽于单元格，接近真实表头的视觉密度
const headerWidth = (c) => `${54 + ((c * 7) % 22)}%`

// 单元格宽度伪随机但稳定：同一位置每次渲染一致，避免骨架自身抖动
const cellWidth = (r, c) => `${44 + ((r * 13 + c * 17) % 40)}%`
</script>

<style scoped>
.skeleton-table table { margin-bottom: 0; }
.skeleton-table th,
.skeleton-table td { vertical-align: middle; }

/* 复用全局 .skeleton 动画，仅覆盖尺寸以贴合单元格行高 */
.sk-line {
  display: block;
  height: 12px;
  width: var(--skeleton-w, 60%);
  border-radius: 6px;
}

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
