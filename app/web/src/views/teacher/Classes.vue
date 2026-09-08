<template>
  <div>
    <div class="page-head classes-head"><div><h2>我的授课班级</h2><p class="muted">从班级题库出发，轻松安排下一次练习。</p></div><router-link class="button-link" to="/teacher/assignment"><Icon icon="mdi:plus" aria-hidden="true" />布置作业</router-link></div>
    <div class="page-toolbar"><span class="muted">{{ loading && !classes.length ? '正在加载班级…' : '共 ' + classes.length + ' 个授课班级' }}</span><label class="search-field"><span class="sr-only">搜索班级</span><Icon icon="mdi:magnify" aria-hidden="true" /><input type="search" v-model.trim="keyword" placeholder="搜索班级名称或代码" /></label></div>
    <div v-if="error" class="error-banner" role="alert"><span>{{ error }}</span><button class="secondary" :disabled="loading" @click="load">重新加载</button></div>
    <div class="class-grid" :aria-busy="loading">
      <template v-if="loading && !classes.length"><div v-for="n in 3" :key="n" class="card loading-card"><div class="skeleton"></div><div class="skeleton"></div></div></template>
      <article v-for="c in filteredClasses" :key="c.teachingClassId" class="class-card">
        <div class="class-top"><div class="class-avatar"><Icon icon="mdi:book-open-page-variant-outline" aria-hidden="true" /></div><span class="chip" :class="c.role === 'PRIMARY' ? 'chip-primary' : 'chip-muted'">{{ c.role === 'PRIMARY' ? '主讲教师' : '助教' }}</span></div>
        <h3>{{ c.name }}</h3><p class="class-code">{{ c.code }}</p>
        <div class="class-footer"><span>班级题库与编程练习</span><router-link :to="`/teacher/classes/${c.teachingClassId}/problems`" class="button-link secondary" :aria-label="'进入' + c.name + '题库'">进入题库<Icon icon="mdi:arrow-right" aria-hidden="true" /></router-link></div>
      </article>
    </div>
    <div v-if="!loading && !error && !filteredClasses.length" class="empty"><Icon icon="mdi:book-open-page-variant-outline" aria-hidden="true" /><h3>{{ classes.length ? '没有找到匹配的班级' : '暂时没有授课班级' }}</h3><p>{{ classes.length ? '试试其他名称或班级代码。' : '请联系管理员分配授课班级，之后即可管理题库和布置作业。' }}</p><button v-if="classes.length" class="secondary" @click="keyword = ''">清除搜索</button></div>
    <p v-if="classes.length" class="class-note"><Icon icon="mdi:information-outline" aria-hidden="true" />需要调整授课班级或教师身份？请联系平台管理员。</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'

const classes = ref([])
const loading = ref(true)
const error = ref('')
const keyword = ref('')
const filteredClasses = computed(() => classes.value.filter(c => (c.name + ' ' + c.code).toLowerCase().includes(keyword.value.toLowerCase())))
async function load() {
  if (loading.value && classes.value.length) return
  loading.value = true
  error.value = ''
  try { classes.value = await api('/teacher/classes') }
  catch (e) { error.value = e.message || '班级加载失败，请重试' }
  finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.classes-head { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.class-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.class-card { padding: 28px; border: 1px solid var(--border); background: var(--panel); border-radius: var(--radius); box-shadow: var(--shadow-sm); }
.class-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.class-avatar { width: 46px; height: 46px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; }
.class-avatar svg { width: 24px; height: 24px; }
.class-card h3 { font-size: 19px; margin: 0 0 8px; }
.class-code { font: 13px/1.6 Consolas, monospace; color: var(--muted); margin: 0 0 28px; }
.class-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 20px; border-top: 1px solid var(--border); }
.class-footer > span { color: var(--muted); font-size: 12px; }
.class-footer .button-link { font-size: 13px; flex-shrink: 0; }
.class-note { display: flex; align-items: flex-start; gap: 8px; margin: 24px 0 0; color: var(--muted); font-size: 12px; }
.class-note svg { flex-shrink: 0; width: 16px; height: 16px; margin-top: 2px; }
.loading-card { display: grid; gap: 20px; height: 250px; }
.loading-card .skeleton { height: 32px; width: 60%; }
@media (max-width: 760px) { .class-grid { grid-template-columns: 1fr; gap: 16px; } .class-card { padding: 24px; } }
@media (max-width: 400px) { .class-footer > span { max-width: 90px; } }
</style>
