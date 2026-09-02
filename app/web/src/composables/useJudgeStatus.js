// 判题状态工具函数
export function useJudgeStatus() {
  // 状态图标映射
  const statusIcons = {
    AC: 'mdi:check-circle',
    WA: 'mdi:close-circle',
    TLE: 'mdi:timer-alert',
    MLE: 'mdi:memory',
    OLE: 'mdi:file-alert',
    CE: 'mdi:hammer-wrench',
    RE: 'mdi:alert-circle',
    PE: 'mdi:format-align-left',
    BSC: 'mdi:shield-alert',
    SE: 'mdi:server-network-off',
    PD: 'mdi:loading'
  }

  // 状态文本映射
  const statusText = {
    AC: '通过',
    WA: '答案错误',
    TLE: '超时',
    MLE: '内存超限',
    OLE: '输出超限',
    CE: '编译错误',
    RE: '运行错误',
    PE: '格式错误',
    BSC: '非法调用',
    SE: '系统错误',
    PD: '判题中'
  }

  // 状态CSS类映射
  const statusClass = {
    AC: 'chip-ac',
    WA: 'chip-wa',
    TLE: 'chip-tle',
    MLE: 'chip-mle',
    OLE: 'chip-ole',
    CE: 'chip-ce',
    RE: 'chip-re',
    PE: 'chip-pe',
    BSC: 'chip-bsc',
    SE: 'chip-se',
    PD: 'chip-pd'
  }

  // 获取状态图标
  function getStatusIcon(status) {
    return statusIcons[status] || 'mdi:help-circle'
  }

  // 获取状态文本
  function getStatusText(status) {
    return statusText[status] || status
  }

  // 获取状态CSS类
  function getStatusClass(status) {
    return statusClass[status] || 'chip-muted'
  }

  // 判断是否为错误状态
  function isErrorStatus(status) {
    return ['WA', 'TLE', 'MLE', 'OLE', 'CE', 'RE', 'PE', 'BSC', 'SE'].includes(status)
  }

  // 判断是否为通过状态
  function isPassedStatus(status) {
    return status === 'AC'
  }

  return {
    getStatusIcon,
    getStatusText,
    getStatusClass,
    isErrorStatus,
    isPassedStatus
  }
}
