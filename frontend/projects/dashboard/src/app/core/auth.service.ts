import { Injectable, signal } from '@angular/core';

/*
 * Sign-in for the dashboard: OpenID Connect authorization code flow with PKCE against the issuer the backend names
 * (/api/auth/config — Keycloak locally, Okta or Entra ID in production). Tokens live in sessionStorage, scoped to
 * the tab; a backend-for-frontend with an HttpOnly cookie would keep them out of JavaScript altogether.
 */

interface AuthConfig {
  enabled: boolean;
  issuer?: string;
  clientId?: string;
}

interface Discovery {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
}

interface Tokens {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_at: number;
}

const KEY = { tokens: 'nms.auth.tokens', state: 'nms.auth.state', verifier: 'nms.auth.verifier', returnTo: 'nms.auth.return' };

@Injectable({ providedIn: 'root' })
export class AuthService {
  /** False when the backend runs with sign-in switched off. */
  readonly enabled = signal(false);
  readonly signedIn = signal(false);

  #config: AuthConfig = { enabled: false };
  #discovery: Discovery | undefined;
  #tokens: Tokens | undefined;
  #refreshing: Promise<boolean> | undefined;

  /** Runs before the app starts: finishes a sign-in coming back from the issuer, or starts one. */
  async init(): Promise<void> {
    this.#config = await untilAnswered('/api/auth/config');
    if (!this.#config.enabled) return;
    this.enabled.set(true);
    this.#discovery = await (await fetch(`${this.#config.issuer}/.well-known/openid-configuration`)).json();

    const url = new URL(location.href);
    const code = url.searchParams.get('code');
    if (code && url.searchParams.get('state') === sessionStorage.getItem(KEY.state)) {
      await this.#exchange(code);
      history.replaceState(null, '', sessionStorage.getItem(KEY.returnTo) ?? '/');
      sessionStorage.removeItem(KEY.state);
      sessionStorage.removeItem(KEY.verifier);
      sessionStorage.removeItem(KEY.returnTo);
      return;
    }

    const saved = sessionStorage.getItem(KEY.tokens);
    if (saved) {
      this.#tokens = JSON.parse(saved);
      if (this.#valid() || (await this.#refresh())) return this.signedIn.set(true);
    }
    await this.login();
  }

  /** A valid access token, refreshed when it is about to expire; undefined when sign-in is off. */
  async token(): Promise<string | undefined> {
    if (!this.enabled()) return undefined;
    if (this.#valid()) return this.#tokens!.access_token;
    if (await this.#refresh()) return this.#tokens!.access_token;
    await this.login();
    return undefined;
  }

  /** Off to the issuer's sign-in page; back to this same URL afterwards. Never resolves — the page navigates away. */
  async login(): Promise<never> {
    const verifier = random(64);
    const state = random(24);
    sessionStorage.setItem(KEY.verifier, verifier);
    sessionStorage.setItem(KEY.state, state);
    sessionStorage.setItem(KEY.returnTo, location.pathname + location.search);
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: this.#config.clientId!,
      redirect_uri: `${location.origin}/`,
      scope: 'openid',
      state,
      code_challenge: await challenge(verifier),
      code_challenge_method: 'S256',
    });
    location.assign(`${this.#discovery!.authorization_endpoint}?${params}`);
    return new Promise<never>(() => {});
  }

  logout(): void {
    const idToken = this.#tokens?.id_token;
    sessionStorage.removeItem(KEY.tokens);
    this.#tokens = undefined;
    const end = this.#discovery?.end_session_endpoint;
    if (!end) return location.assign('/');
    const params = new URLSearchParams({ client_id: this.#config.clientId!, post_logout_redirect_uri: `${location.origin}/` });
    if (idToken) params.set('id_token_hint', idToken);
    location.assign(`${end}?${params}`);
  }

  async #exchange(code: string): Promise<void> {
    await this.#tokenRequest({
      grant_type: 'authorization_code',
      code,
      redirect_uri: `${location.origin}/`,
      client_id: this.#config.clientId!,
      code_verifier: sessionStorage.getItem(KEY.verifier) ?? '',
    });
    this.signedIn.set(true);
  }

  #refresh(): Promise<boolean> {
    const refreshToken = this.#tokens?.refresh_token;
    if (!refreshToken) return Promise.resolve(false);
    // One refresh at a time, however many requests are waiting on it.
    this.#refreshing ??= this.#tokenRequest({ grant_type: 'refresh_token', refresh_token: refreshToken, client_id: this.#config.clientId! })
      .then(() => true, () => false)
      .finally(() => (this.#refreshing = undefined));
    return this.#refreshing;
  }

  async #tokenRequest(fields: Record<string, string>): Promise<void> {
    const res = await fetch(this.#discovery!.token_endpoint, { method: 'POST', body: new URLSearchParams(fields) });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error_description ?? body.error);
    this.#tokens = {
      access_token: body.access_token,
      refresh_token: body.refresh_token ?? this.#tokens?.refresh_token,
      id_token: body.id_token ?? this.#tokens?.id_token,
      expires_at: Date.now() + body.expires_in * 1000,
    };
    sessionStorage.setItem(KEY.tokens, JSON.stringify(this.#tokens));
  }

  #valid(): boolean {
    return !!this.#tokens && this.#tokens.expires_at - Date.now() > 30_000;
  }
}

/** The first call waits on a backend that may be asleep: retried until it answers, for up to two minutes. */
async function untilAnswered<T>(url: string): Promise<T> {
  const giveUpAt = Date.now() + 120_000;
  for (;;) {
    const res = await fetch(url).catch(() => undefined);
    if (res?.ok) return res.json();
    if (res && res.status < 500) throw new Error(`${url} answered ${res.status}`);
    if (Date.now() > giveUpAt) throw new Error(`${url} did not answer`);
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
}

function random(length: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return base64url(bytes).slice(0, length);
}

async function challenge(verifier: string): Promise<string> {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
}

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
