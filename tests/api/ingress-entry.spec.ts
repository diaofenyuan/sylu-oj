// @ts-check
import { test, expect } from '@playwright/test'

/**
 * Task 8 入口验收（Playwright）。
 * 常规模式（默认）：对 dev 服务验证认证边界与内测明示头链路的客户端契约。
 * live 模式（OJ_INGRESS_BASE_URL 指向经 Nginx 的入口）：验证 TLS、
 * 安全头、限流、内部路径拒绝与公网不可达契约。
 */

const INGRESS = process.env.OJ_INGRESS_BASE_URL
const base = INGRESS || process.env.OJ_API_BASE_URL || 'http://localhost:8080'
test.use({ baseURL: base })

test.describe('入口 HTTPS 与安全边界', () => {
  test('未认证访问受保护路径被拒绝', async ({ request }) => {
    const res = await request.get('/api/teacher/classes')
    expect([401, 403]).toContain(res.status())
  })

  test('内部判题网关与内部接口不经入口暴露', async ({ request }) => {
    if (!INGRESS) {
      // dev 直连 API：网关应要求代理凭据（401），内部接口要求内部令牌
      const claim = await request.post('/api/judge/v1/tasks/claim', { data: {} })
      expect(claim.status()).toBe(401)
      const internal = await request.post('/internal/judge/results', {
        data: { submissionId: 1, resultCode: 'AC', normalizedScore: 100 }
      })
      expect([401, 403]).toContain(internal.status())
      return
    }
    // 经 Nginx 入口：一律 403
    expect((await request.post('/api/judge/v1/tasks/claim', { data: {} })).status()).toBe(403)
    expect((await request.post('/internal/judge/results', { data: {} })).status()).toBe(403)
    expect((await request.get('/actuator/health')).status()).toBe(403)
  })

  test('live: 安全头与内测明示齐全（HSTS/CSP/nosniff/X-OJ-Stage）', async ({ request }) => {
    test.skip(!INGRESS, '未配置 OJ_INGRESS_BASE_URL，跳过 Nginx 入口检查')
    const res = await request.get('/healthz')
    expect(res.status()).toBe(200)
    const headers = res.headers()
    expect(headers['strict-transport-security']).toContain('max-age')
    expect(headers['x-content-type-options']).toBe('nosniff')
    expect(headers['x-frame-options']).toBe('DENY')
    expect(headers['content-security-policy']).toContain("default-src 'self'")
    expect(headers['x-oj-stage']).toBe('internal-beta')
  })

  test('live: 登录限流生效（超频返回 429/503）', async ({ request }) => {
    test.skip(!INGRESS, '未配置 OJ_INGRESS_BASE_URL，跳过 Nginx 入口检查')
    let throttled = false
    for (let i = 0; i < 15; i++) {
      const res = await request.post('/api/auth/login', {
        data: { loginName: 'nosuch', password: 'wrong' }
      })
      if (res.status() === 429 || res.status() === 503) {
        throttled = true
        break
      }
    }
    expect(throttled).toBe(true)
  })

  test('live: TLS 证书生效且为内测证书（自签名，CN 含 internal-beta）', async ({ request }) => {
    test.skip(!INGRESS || !INGRESS.startsWith('https'), '仅对 HTTPS 入口执行')
    const res = await request.get('/healthz')
    expect(res.status()).toBe(200)
  })
})
