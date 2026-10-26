import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import App from './App';
import i18n from './i18n';
beforeEach(async () => { await i18n.changeLanguage('zh'); localStorage.clear(); vi.restoreAllMocks(); });
it('switches language, logs in and logs out', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: !url.endsWith('/me'), status: url.endsWith('/me') ? 401 : 200, json: async () => ({token:'csrf'}) })));
  render(<App />);
  await screen.findByLabelText('用户名');
  fireEvent.click(screen.getByRole('button', {name: 'English'}));
  fireEvent.change(screen.getByLabelText('Username'), {target:{value:'admin'}});
  fireEvent.change(screen.getByLabelText('Password'), {target:{value:'123456'}});
  fireEvent.click(screen.getByRole('button', {name:'Sign in'}));
  await screen.findByRole('button', {name:'Sign out'});
  fireEvent.click(screen.getByRole('button', {name:'Sign out'}));
  await screen.findByRole('button', {name:'Sign in'});
});
it('shows invalid credentials', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ok: !url.endsWith('/login') && !url.endsWith('/me'), status:401, json:async()=>({token:'csrf'})})));
  render(<App />);
  await screen.findByLabelText('用户名');
  fireEvent.change(screen.getByLabelText('用户名'), {target:{value:'admin'}});
  fireEvent.change(screen.getByLabelText('密码'), {target:{value:'bad'}});
  fireEvent.click(screen.getByRole('button', {name:'登录'}));
  await waitFor(()=>expect(screen.getByRole('alert').textContent).toBe('用户名或密码错误'));
});
